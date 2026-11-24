package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.keeps
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.archive.EpubArchive
import app.tfdn.lirelle.core.reader.archive.EpubCheckResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.archive.checkEpub
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import app.tfdn.lirelle.feature.reader.web.MinimumWebViewMajorVersion
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** The major version of the system's web view, null when it cannot be known. */
fun interface WebViewVersion {
    fun major(): Int?
}

/**
 * Whether the signed-in account may download files, which the text of a read-along book needs: without it the
 * reader opens the plain EPUB of the book, when it has one.
 */
fun interface DownloadPermission {
    suspend fun allowed(): Boolean
}

sealed interface EpubOpening {
    data class Opened(val archive: EpubArchive) : EpubOpening

    data class Refused(val problem: EpubProblem) : EpubOpening
}

/** Checks a downloaded file and opens it as an archive. */
fun interface EpubOpener {
    suspend fun open(file: File): EpubOpening
}

/** The check first: the archive's constructor throws on what is not a zip. */
internal class CheckedEpubOpener(private val io: CoroutineDispatcher) : EpubOpener {
    override suspend fun open(file: File): EpubOpening = withContext(io) {
        when (val check = checkEpub(file)) {
            is EpubCheckResult.Refused -> EpubOpening.Refused(check.problem)
            EpubCheckResult.Ok -> try {
                EpubOpening.Opened(EpubArchive(file))
            } catch (_: IOException) {
                EpubOpening.Refused(EpubProblem.NotAnArchive)
            }
        }
    }
}

/**
 * A book ready to be read: what the page reads it from (the archive of an EPUB, open, or the whole file of a
 * book of another format) and its place in the cache, held. [close] gives both back, once, however many times
 * it is called.
 */
class OpenedBook internal constructor(
    val detail: BookDetail,
    val file: BookFile,
    val content: BookContent,
    private val cache: ReaderCache,
    private val key: CachedBookKey,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        (content as? BookContent.Epub)?.archive?.close()
        cache.release(key)
    }
}

sealed interface OpeningStep {
    /** The book's details are known. */
    data class Found(val detail: BookDetail) : OpeningStep

    data class Progress(val progress: DownloadProgress) : OpeningStep

    data class Failed(val reason: ReaderFailure) : OpeningStep

    /** The last step. The book now belongs to the collector, which closes it. */
    data class Ready(val book: OpenedBook) : OpeningStep
}

/**
 * From a book's id to what the page reads: details, web view check, choice of the file, download, and for an
 * EPUB its checks. A Kindle book or a FictionBook is handed over as the file it is: the engine says whether it
 * reads it.
 *
 * A downloaded book needs no server: its page is the one the download kept when the server cannot be reached,
 * or no longer has the book, and the cache hands out its file as the device keeps it.
 */
class BookOpener @Inject constructor(
    private val library: LibraryRepository,
    private val cache: ReaderCache,
    private val epubs: EpubOpener,
    private val webView: WebViewVersion,
    private val kept: KeptBookData,
    private val downloads: Downloads,
    private val permission: DownloadPermission = DownloadPermission { true },
) {
    fun open(bookId: BookId): Flow<OpeningStep> = flow {
        val detail = when (val answer = detail(bookId)) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> {
                emit(OpeningStep.Failed(ReaderFailure.Download(answer.error)))
                return@flow
            }
        }
        emit(OpeningStep.Found(detail))
        val file = detail.readerFile(canDownload = readAlongOpens(bookId))
        val failure = when {
            webView.major()?.let { it < MinimumWebViewMajorVersion } == true -> ReaderFailure.OldWebView
            file == null -> ReaderFailure.NoEpub
            else -> null
        }
        if (file == null || failure != null) {
            emit(OpeningStep.Failed(failure ?: ReaderFailure.NoEpub))
            return@flow
        }
        val key = CachedBookKey(file.id, file.contentVersion)
        // A read-along file carries its audio: the text alone is what the reader needs.
        cache.open(bookId, key, audioless = file.hasReadAlong).collect { state ->
            when (state) {
                is CacheState.Downloading -> emit(OpeningStep.Progress(state.progress))
                is CacheState.Failed -> emit(OpeningStep.Failed(fetchFailure(file, state.error)))
                is CacheState.Ready -> {
                    // At once, before anything suspends: the cache keeps nothing for a reader that went away.
                    cache.hold(key)
                    handOver(opened(detail, file, key, state.file))
                }
            }
        }
    }

    /**
     * Whether a read-along file can be had: the account may download, or the device keeps the text already and
     * nothing is asked of the server. When it cannot, the book's plain EPUB is the one opened, if it has one:
     * this is known before the server is asked, not from its refusal.
     */
    private suspend fun readAlongOpens(bookId: BookId): Boolean =
        permission.allowed() || downloads.keeps(bookId, DownloadFormat.Text)

    /**
     * Why the file could not be fetched. A read-along book is asked without its audio, which the server refuses
     * to an account that may not download: that is said, instead of an error of the server. A server that
     * cannot be reached stays what it is.
     */
    private suspend fun fetchFailure(file: BookFile, error: ApiError): ReaderFailure {
        val refused = file.hasReadAlong && error != ApiError.ServerUnreachable && !permission.allowed()
        return if (refused) ReaderFailure.DownloadNotAllowed else ReaderFailure.Download(error)
    }

    /** The server's page of the book; the one kept with its download when the server cannot give it. */
    private suspend fun detail(bookId: BookId): ApiResult<BookDetail> {
        val answer = library.book(bookId)
        val error = (answer as? ApiResult.Failure)?.error
        if (error != ApiError.ServerUnreachable && error != ApiError.NotFound) return answer
        return kept.detail(bookId)?.let { ApiResult.Success(it) } ?: answer
    }

    /**
     * A book that comes out ready was opened: its download, if it has one, is told. A book whose collector is
     * cancelled as it is handed over is closed here; closing it twice does no harm.
     */
    private suspend fun FlowCollector<OpeningStep>.handOver(step: OpeningStep) {
        try {
            if (step is OpeningStep.Ready) markOpened(step.book.detail.id)
            emit(step)
        } catch (cancelled: CancellationException) {
            (step as? OpeningStep.Ready)?.book?.close()
            throw cancelled
        }
    }

    /** A date that cannot be written keeps nobody from reading. */
    private suspend fun markOpened(bookId: BookId) {
        try {
            downloads.markOpened(bookId)
        } catch (_: IOException) {
            // The registry's file: the book is only not counted as opened.
        }
    }

    /**
     * An EPUB is checked and opened. A file of another format is handed over as the cache has it: nothing in
     * it is looked at here.
     */
    private suspend fun opened(detail: BookDetail, file: BookFile, key: CachedBookKey, onDisk: File): OpeningStep {
        if (file.isEpub) return checked(detail, file, key, onDisk)
        return OpeningStep.Ready(OpenedBook(detail, file, BookContent.Whole(onDisk, file.pageFormat), cache, key))
    }

    /**
     * The book is held from here on: it is released when the file is refused, or the check cancelled.
     *
     * Reading a file that is not what it claims to be can throw anything: the platform's zip and
     * XML readers have unchecked exceptions of their own for some malformed input. Whatever is
     * thrown here is about the file, and refuses it.
     */
    private suspend fun checked(detail: BookDetail, file: BookFile, key: CachedBookKey, onDisk: File): OpeningStep {
        val opening = try {
            epubs.open(onDisk)
        } catch (cancelled: CancellationException) {
            cache.release(key)
            throw cancelled
        } catch (_: RuntimeException) {
            EpubOpening.Refused(EpubProblem.NotAnEpub)
        }
        return when (opening) {
            is EpubOpening.Opened ->
                OpeningStep.Ready(OpenedBook(detail, file, BookContent.Epub(opening.archive), cache, key))
            is EpubOpening.Refused -> refused(key, opening.problem)
        }
    }

    /**
     * A file that is not a book is not kept: under the same version the cache would hand the same
     * bytes again at every try. A protected book is what the server has, and stays.
     */
    private suspend fun refused(key: CachedBookKey, problem: EpubProblem): OpeningStep {
        cache.release(key)
        if (problem == EpubProblem.Protected) return OpeningStep.Failed(ReaderFailure.Protected)
        cache.discard(key)
        return OpeningStep.Failed(ReaderFailure.NotAnEpub)
    }
}
