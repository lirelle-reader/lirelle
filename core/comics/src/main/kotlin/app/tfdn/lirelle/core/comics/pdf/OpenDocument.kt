package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.DocumentFailure
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A document's file as the cache gave it.
 *
 * @property kept whether it is the copy the downloads keep of the book.
 * @property version what tells this document from another of the same file: the version the server said for a
 *   file fetched from it; for a kept copy, which is opened whatever its version, what the copy itself is.
 */
internal class GivenFile(val file: File, val kept: Boolean, val version: String?)

/**
 * What tells one kept copy of a file from the one that replaces it: its size and the moment it was written. The
 * downloads write a new file for a new version, and never touch one that is whole.
 */
private fun keptVersion(copy: File): String = "kept-${copy.length()}-${copy.lastModified()}"

/**
 * Where a PDF's whole file comes from: the reader's cache of whole files. It hands out the copy the downloads
 * keep of the book first, as it is and with no network, and fetches the file otherwise. The file is held from
 * the moment it is given until [release]: the cache's own is not evicted, and a kept book is in use, so that
 * a deletion asked meanwhile waits for the reader to leave. Once the reader left ([close]) no file is held any
 * more, one that comes after it included.
 */
internal class DocumentSource(private val key: ComicKey, private val cache: ReaderCache) {
    private val cacheKey = CachedBookKey(key.fileId, key.contentVersion)
    private var held = false
    private var left = false

    /** The file, [onProgress] being told what came of it while it is fetched, and null once nothing is awaited. */
    suspend fun file(onProgress: (OpeningProgress?) -> Unit): ApiResult<GivenFile> {
        // The cache files a document under its book: without one there is nowhere to take it from.
        val bookId = key.bookId ?: return ApiResult.Failure(ApiError.Unexpected)
        var answer: ApiResult<GivenFile> = ApiResult.Failure(ApiError.Unexpected)
        try {
            cache.open(bookId, cacheKey, audioless = false).collect { state ->
                when (state) {
                    is CacheState.Downloading ->
                        onProgress(OpeningProgress(state.progress.bytesRead, state.progress.totalBytes))
                    is CacheState.Ready -> {
                        // At once, as the cache asks: it keeps the file for the flow's time only.
                        if (hold()) answer = ApiResult.Success(given(state))
                    }
                    is CacheState.Failed -> answer = ApiResult.Failure(state.error)
                }
            }
        } finally {
            onProgress(null)
        }
        return answer
    }

    private fun given(state: CacheState.Ready): GivenFile =
        GivenFile(state.file, state.kept, if (state.kept) keptVersion(state.file) else key.contentVersion)

    /** Holds the file just given; false when the reader left, and there is nobody to give it back. */
    @Synchronized
    private fun hold(): Boolean {
        if (!left && !held) {
            held = true
            cache.hold(cacheKey)
        }
        return !left
    }

    /** Gives the file back; false when none was given, or when it was given back already. */
    @Synchronized
    fun release(): Boolean {
        val given = held
        held = false
        if (given) cache.release(cacheKey)
        return given
    }

    /** The reader left: the file is given back, and none is held from now on. */
    @Synchronized
    fun close() {
        left = true
        release()
    }

    /**
     * The file is no document: the cache's own goes, for a whole one to be fetched the next time. The copy the
     * downloads keep is theirs, and stays.
     */
    suspend fun discard() {
        if (release()) cache.discard(cacheKey)
    }
}

/**
 * A document open, and its page count, read once: the document is the drawer's alone after that.
 *
 * @property version what tells this document from another of the same file, as [GivenFile.version] says it.
 */
internal class Opened(val document: PdfDocument, val count: Int, val version: String?)

/**
 * The document of one opening of a PDF: fetched and opened when its page count is first asked, closed with
 * the reader. A failure is not remembered: the next call fetches, or opens, again.
 */
internal class OpenDocument(private val source: DocumentSource, private val documents: PdfDocuments) {
    private val asking = Mutex()
    private val opened = MutableStateFlow<Opened?>(null)
    private var closed = false

    /** What came of the document while it is fetched. */
    val progress = MutableStateFlow<OpeningProgress?>(null)

    /** The document, once it is open. */
    suspend fun await(): Opened = opened.filterNotNull().first()

    suspend fun count(): PageCountResult = asking.withLock {
        opened.value?.let { PageCountResult.Known(it.count) } ?: open()
    }

    /**
     * The system's renderer throws more than it says for a file it cannot read: whatever it is, the document is
     * damaged, and the app goes on.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun open(): PageCountResult {
        val given = when (val fetched = source.file { progress.value = it }) {
            is ApiResult.Success -> fetched.value
            is ApiResult.Failure -> return PageCountResult.Failed(fetched.error)
        }
        val document = try {
            documents.open(given.file)
        } catch (_: SecurityException) {
            source.release()
            return PageCountResult.Unreadable(DocumentFailure.Protected)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
            source.discard()
            return PageCountResult.Unreadable(if (given.kept) DocumentFailure.KeptDamaged else DocumentFailure.Damaged)
        }
        val count = document.pageCount
        if (!keep(Opened(document, count, given.version))) {
            document.close()
            source.release()
        }
        return PageCountResult.Known(count)
    }

    /** False when the reader left while the document was opened: nobody would close it. */
    @Synchronized
    private fun keep(document: Opened): Boolean {
        if (!closed) opened.value = document
        return !closed
    }

    /** Closes the document and gives its file back. Called once nothing draws any more. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        opened.value?.document?.close()
        source.close()
    }
}
