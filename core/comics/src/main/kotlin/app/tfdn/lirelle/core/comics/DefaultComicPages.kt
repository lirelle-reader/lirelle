package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.comics.fetch.AlbumSource
import app.tfdn.lirelle.core.comics.fetch.Command
import app.tfdn.lirelle.core.comics.fetch.PageLoader
import app.tfdn.lirelle.core.comics.fetch.PageStates
import app.tfdn.lirelle.core.comics.kept.KeptCopies
import app.tfdn.lirelle.core.comics.kept.KeptPageLoader
import app.tfdn.lirelle.core.comics.store.Album
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.offline.KeptPages
import app.tfdn.lirelle.core.network.ApiResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * @param source the profile's source, which serves the pages of a comic and of a DjVu: the same pages, store and
 * window for both.
 * @param pdf opens a PDF, whose pages are drawn on the device.
 * @param copies the pages the downloads keep of an album, read before the server is asked for any.
 * @param io where the server is asked and files are read and written.
 */
internal class DefaultComicPagesOpener(
    private val store: ComicStore,
    private val source: PageSource,
    private val pdf: ComicPagesOpener,
    private val copies: KeptCopies,
    private val io: CoroutineDispatcher,
) : ComicPagesOpener {
    override fun open(key: ComicKey, scope: CoroutineScope): ComicPages = when (key.kind) {
        PagedKind.Comic, PagedKind.Djvu ->
            DefaultComicPages(key, scope, AlbumParts(store, AlbumSource(source, key.kind), copies), io)
        PagedKind.Pdf -> pdf.open(key, scope)
    }
}

/**
 * What the pages of an album are made with.
 *
 * @param store where the pages fetched are kept.
 * @param source the server's side of the album's kind.
 * @param copies the pages the downloads keep of the albums.
 */
internal class AlbumParts(val store: ComicStore, val source: AlbumSource, val copies: KeptCopies)

/**
 * What the reader says is put in a line, and one coroutine of [scope] takes it from there, one thing at a
 * time: it alone decides what is fetched. The fetches are its children, so they end with it.
 *
 * An album the downloads keep whole is read from its kept pages, where they are, whatever their version: the
 * count is theirs, nothing is asked of the server, nothing goes to the store of the pages seen, and the book
 * is in use until the reader leaves. Whether the album is kept is looked up first, once, off the main thread;
 * what the reader says meanwhile waits in the line. One the device does not keep whole is fetched.
 */
internal class DefaultComicPages(
    key: ComicKey,
    scope: CoroutineScope,
    parts: AlbumParts,
    private val io: CoroutineDispatcher,
) : ComicPages {
    private val store = parts.store

    /** Held at once: the store must not drop the album between its opening and its first fetch. */
    private val album = store.hold(key)
    private val released = AtomicBoolean()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val states = PageStates()
    private val count = PageCount(album, parts.source)

    /** The pages the downloads keep of the album; null, once known, when it is fetched. */
    private val kept = CompletableDeferred<KeptPages?>()

    private val work: Job = scope.launch(io) {
        parts.copies.reading(key) { pages ->
            kept.complete(pages)
            if (pages == null) {
                store.prepare(album)
                val loader = PageLoader(album, parts.source, store, states, commands) { count.known }
                for (command in commands) loader.handle(command, this)
            } else {
                // Nothing of a kept album is the store's: it is not held there.
                release()
                val loader = KeptPageLoader(pages, states)
                for (command in commands) loader.handle(command)
            }
        }
    }.also {
        it.invokeOnCompletion {
            // The reader left before the look ended: nobody is kept waiting for it.
            kept.complete(null)
            release()
        }
    }

    /** The pages are fetched one by one, or kept: there is no document to wait for. */
    override val opening: Flow<OpeningProgress?> = flowOf(null)

    override suspend fun pageCount(): PageCountResult = withContext(io) {
        val pages = kept.await()
        when (val answer = pages?.let { ApiResult.Success(it.count) } ?: count.get()) {
            is ApiResult.Success -> PageCountResult.Known(answer.value)
            is ApiResult.Failure -> PageCountResult.Failed(answer.error)
        }
    }

    override fun page(index: Int): Flow<PageState> = flow {
        commands.trySend(Command.Restore(index))
        emitAll(states.of(index))
    }

    override fun show(index: Int) {
        commands.trySend(Command.Show(index))
    }

    override fun settle(index: Int) {
        commands.trySend(Command.Settle(index))
    }

    override fun jump(index: Int) {
        commands.trySend(Command.Jump(index))
    }

    override fun retry(index: Int) {
        commands.trySend(Command.Show(index))
    }

    /** The server's picture, fetched or kept, is the only one there is. */
    override fun sharpen(index: Int, longSidePx: Int) = Unit

    override fun close() {
        release()
        work.cancel()
    }

    /** Once, whether the reader closed the album or its scope ended. */
    private fun release() {
        if (released.compareAndSet(false, true)) store.release(album)
    }
}

/** The album's page count: asked once, noted with the album's pages for the day the server does not answer. */
private class PageCount(private val album: Album, private val source: AlbumSource) {
    private val asking = Mutex()

    @Volatile
    var known: Int? = null
        private set

    suspend fun get(): ApiResult<Int> = asking.withLock {
        known?.let { return ApiResult.Success(it) }
        val answer = when (val asked = source.pageCount(album.fileId)) {
            is ApiResult.Success -> asked.also { album.rememberCount(it.value) }
            is ApiResult.Failure -> album.rememberedCount()?.let { ApiResult.Success(it) } ?: asked
        }
        if (answer is ApiResult.Success) known = answer.value
        answer
    }
}
