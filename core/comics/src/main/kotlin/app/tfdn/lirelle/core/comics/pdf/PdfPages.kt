package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.ComicPagesOpener
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.fetch.PageStates
import app.tfdn.lirelle.core.comics.store.Album
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the pages of a PDF are made with.
 *
 * @param store where the pages drawn are kept, with the pages fetched of the other kinds.
 * @param cache the reader's cache of whole files, which hands out the copy the device keeps of a downloaded
 *   book, and fetches the document otherwise.
 * @param io where files are read and written, and the pages drawn.
 */
internal class PdfParts(
    val store: ComicStore,
    val cache: ReaderCache,
    val documents: PdfDocuments,
    val io: CoroutineDispatcher,
)

internal class PdfPagesOpener(private val parts: PdfParts) : ComicPagesOpener {
    override fun open(key: ComicKey, scope: CoroutineScope): ComicPages = PdfPages(key, scope, parts)
}

/**
 * The pages of a PDF, drawn on the device from its whole file and kept as pictures in the page store.
 *
 * What the reader says is put in a line, and one coroutine of the scope takes it from there: it draws one page
 * at a time, since the system's renderer allows no more, and reads between two drawings what was said
 * meanwhile. So the page the reader arrives on waits for the drawing being made, and for no other.
 */
internal class PdfPages(key: ComicKey, scope: CoroutineScope, private val parts: PdfParts) : ComicPages {
    private val album = ReaderAlbum(parts.store)
    private val commands = Channel<DrawCommand>(Channel.UNLIMITED)
    private val states = PageStates()
    private val document = OpenDocument(DocumentSource(key, parts.cache), parts.documents)

    /** The document is closed once this has ended: no drawing is being made then. */
    private val work: Job = scope.launch(parts.io) {
        val opened = document.await()
        // The pages are filed under the document they are drawn from: a kept copy may not be the server's version.
        val album = album.hold(key.copy(contentVersion = opened.version)) ?: return@launch
        parts.store.prepare(album)
        val drawer = PageDrawer(album, parts.store, states, opened)
        while (true) {
            ensureActive()
            while (true) drawer.handle(commands.tryReceive().getOrNull() ?: break)
            if (!drawer.drawNext()) drawer.handle(commands.receive())
        }
    }.also {
        it.invokeOnCompletion {
            album.release()
            document.close()
        }
    }

    override val opening: Flow<OpeningProgress?> get() = document.progress

    override suspend fun pageCount(): PageCountResult = withContext(parts.io) { document.count() }

    override fun page(index: Int): Flow<PageState> = flow {
        commands.trySend(DrawCommand.Restore(index))
        emitAll(states.of(index))
    }

    override fun show(index: Int) {
        commands.trySend(DrawCommand.Show(index))
    }

    override fun settle(index: Int) {
        commands.trySend(DrawCommand.Settle(index))
    }

    override fun jump(index: Int) {
        commands.trySend(DrawCommand.Jump(index))
    }

    override fun retry(index: Int) {
        commands.trySend(DrawCommand.Show(index))
    }

    override fun sharpen(index: Int, longSidePx: Int) {
        commands.trySend(DrawCommand.Sharpen(index, longSidePx))
    }

    /** The drawing being made ends first; the document is closed and its file given back after it. */
    override fun close() {
        album.release()
        work.cancel()
    }
}

/**
 * The album of one reader of a document, known once the document is open: held from then, so that the store
 * does not drop it while it is read, and given back once, whether the reader closed the document or its scope
 * ended. It is not held at all once the reader left.
 */
private class ReaderAlbum(private val store: ComicStore) {
    private var held: Album? = null
    private var left = false

    /** Null when the reader left: there is nothing to draw for. */
    @Synchronized
    fun hold(key: ComicKey): Album? = if (left) null else store.hold(key).also { held = it }

    @Synchronized
    fun release() {
        left = true
        held?.let(store::release)
        held = null
    }
}
