package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import okhttp3.Response

private const val Unauthorized = 401
private const val NotFound = 404
private val ServerErrors = 500..599

/**
 * Where the page [index] of the file [fileId] is served by a source of the tests, on [server]: under no server's
 * routes. The source serves comics and DjVus by pages, and a PDF whole.
 */
internal fun testPageUrl(server: String, kind: PagedKind, fileId: FileId, index: Int): String? =
    if (kind == PagedKind.Pdf) null else "$server/pages/${kind.name.lowercase()}/$fileId/$index"

/**
 * The addresses a source of the tests gives the kept files: those of the repositories' fakes for a whole file,
 * an audio asset and a read-along track, and [testPageUrl] on [server] for a page.
 *
 * @param server the profile's server, read on every call; null once signed out.
 */
internal class TestLocations(
    private val server: () -> String?,
    private val reader: ReaderRepository,
    private val audio: AudioRepository,
    private val readAlong: ReadAlongRepository,
) : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = when (source) {
        is FileSource.Whole -> reader.fileUrl(source.fileId, audioless = false)
        is FileSource.Audioless -> reader.fileUrl(source.fileId, audioless = true)
        is FileSource.Page -> server()?.let { testPageUrl(it, source.kind, source.fileId, source.index) }
        is FileSource.AudioAsset -> audio.assetUrl(bookId, source.assetId)
        is FileSource.ReadAlongTrack -> readAlong.trackUrl(bookId, source.fileId, source.href)
    }
}

/** The addresses a profile of [server] (no `/` at its end) gives its kept files: see [TestLocations]. */
internal fun testLocations(server: String): DownloadLocations = TestLocations(
    { server },
    FakeReaderRepository().apply { this.server = server },
    FakeAudioRepository().apply { serverUrl = server },
    FakeReadAlongRepository().apply { serverUrl = server },
)

/**
 * What a source of the tests says its server's refusals are, by their status alone: 401 the session's end, 404
 * nothing there, 5xx the server's failure, anything else unexpected. It notes each refusal it reads, with its
 * body.
 */
internal class TestRefusals : DownloadRefusals {
    /** The status and the body of each refusal read, in order. */
    val read = mutableListOf<Pair<Int, String>>()

    /** The status the source reads as a refusal of the account's rights; none unless a test says so. */
    var notAllowed: Int? = null

    override fun failure(response: Response): DownloadFailure =
        if (response.code == notAllowed) DownloadFailure.NotAllowed else super.failure(response)

    override fun refusal(response: Response): ApiError {
        read += response.code to response.peekBody(Long.MAX_VALUE).string()
        return when (response.code) {
            Unauthorized -> ApiError.SessionExpired
            NotFound -> ApiError.NotFound
            in ServerErrors -> ApiError.ServerFailed
            else -> ApiError.Unexpected
        }
    }
}
