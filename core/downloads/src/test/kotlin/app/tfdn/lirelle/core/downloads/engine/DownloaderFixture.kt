package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/** The book the downloader fetches for. */
internal val TheBook = BookId("3")

/** A server, a place for the files and the downloader between them. */
internal class DownloaderFixture : ExternalResource() {
    private val folder = TemporaryFolder()
    val server = MockWebServer()
    var freeBytes = Long.MAX_VALUE
    var now: () -> Long = { 0L }
    lateinit var files: DownloadFiles
    lateinit var downloader: Downloader

    /** Where the files are served: those of a source of the tests, on this server, unless a test says otherwise. */
    lateinit var locations: DownloadLocations

    /** What the source says of the server's refusals. */
    val refusals = TestRefusals()

    override fun before() {
        folder.create()
        server.start()
        files = DownloadFiles(File(folder.root, "downloads"))
        locations = testLocations(server.url("/").toString().trimEnd('/'))
        downloader = Downloader(
            client = OkHttpClient(),
            files = files,
            locations = { bookId, source -> locations.url(bookId, source) },
            refusals = refusals,
            io = Dispatchers.IO,
            freeBytes = { freeBytes },
            now = { now() },
        )
    }

    override fun after() {
        server.close()
        folder.delete()
    }

    /** An audiobook file, as the asset route serves it. */
    fun audio(version: String? = "v1", expectedBytes: Long? = null, resumable: Boolean = true) = PlannedFile(
        name = "1-a",
        source = FileSource.AudioAsset("a"),
        version = version,
        expectedBytes = expectedBytes,
        resumable = resumable,
        kind = FileKind.Audio,
    )

    fun epub() = PlannedFile(
        name = "12.epub",
        source = FileSource.Whole(FileId("12")),
        version = "v1",
        expectedBytes = null,
        resumable = true,
        kind = FileKind.Epub,
    )

    suspend fun fetch(
        file: PlannedFile,
        format: DownloadFormat = DownloadFormat.Audiobook,
        onProgress: (Long) -> Unit = {},
    ): FetchResult = downloader.fetch(TheBook, format, file, onProgress)

    fun target(file: PlannedFile, format: DownloadFormat = DownloadFormat.Audiobook): File =
        files.file(TheBook, format, file.name)

    fun part(file: PlannedFile, format: DownloadFormat = DownloadFormat.Audiobook): File =
        files.part(TheBook, format, file.name)

    /** The sidecar of the plan: the version the part belongs to, beside it. */
    fun partVersion(file: PlannedFile, format: DownloadFormat = DownloadFormat.Audiobook): File =
        File(part(file, format).path + ".version")

    /** What a cut download left: [bytes] of [file], of [version]. */
    fun leavePart(file: PlannedFile, bytes: ByteArray, version: String? = file.version) {
        part(file).apply { checkNotNull(parentFile).mkdirs() }.writeBytes(bytes)
        if (version != null) partVersion(file).writeText(version)
    }
}

internal fun bytes(count: Int): ByteArray = ByteArray(count) { (it % 251).toByte() }

internal fun whole(bytes: ByteArray): MockResponse.Builder = MockResponse.Builder().body(Buffer().write(bytes))

/**
 * Serves [bytes] as the asset and track routes do: a range from any offset is answered 206 with
 * `content-range: bytes <from>-<to>/<total>`. The first [cuts] answers lose their connection on the way.
 */
internal class RangedFile(
    private val bytes: ByteArray,
    private val cuts: Int = 0,
    private val honoursRanges: Boolean = true,
) : Dispatcher() {
    /** The `Range` header of each request, in order. */
    val ranges = mutableListOf<String?>()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val range = request.headers["Range"]
        ranges += range
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?.takeIf { honoursRanges }
        val answer = if (from == null) {
            whole(bytes)
        } else {
            MockResponse.Builder().code(PartialContent)
                .addHeader("content-range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                .addHeader("accept-ranges", "bytes")
                .body(Buffer().write(bytes, from, bytes.size - from))
        }
        if (ranges.size <= cuts) answer.onResponseBody(SocketEffect.ShutdownConnection)
        return answer.build()
    }
}

private const val PartialContent = 206

private const val Container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

/** The least a zip needs to pass for an EPUB, with [more] entries added or replacing those. */
internal fun epubBytes(vararg more: Pair<String, String>): ByteArray {
    val entries = mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to Container,
        "OEBPS/content.opf" to "<package/>",
    ) + more
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out ->
        entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.toByteArray())
            out.closeEntry()
        }
    }
    return bytes.toByteArray()
}
