package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.route
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A chapter of the test server: library 1, series 7, volume 7, chapter 44, file 44 (a CBZ, made an EPUB here). */
private val Album = FileId("1-7-7-44-44")

/** The PDF chapter of the test server: chapter 1355. */
private val Pdf = FileId("1-7-7-1355-1355")

private const val Chapter = "GET /api/Chapter"
private const val Download = "GET /api/Download/chapter"
private const val PdfRoute = "GET /api/Reader/pdf"

/** The EPUB and PDF files of Kavita, fetched into the sink the reader cache gives. */
class KavitaReaderDownloadTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var reader: KavitaReader

    @Before
    fun setUp() {
        server.start()
        routes = KavitaRoutes(server)
        reader = routes.reader()
    }

    @After
    fun tearDown() = server.close()

    /** The comic's chapter with its file an EPUB. */
    private fun epub() {
        val chapter = Json.parseToJsonElement(fixtureBody("chapter-asterix")).jsonObject
        val file = chapter.getValue("files").jsonArray.first().jsonObject
        val epubFile = JsonObject(file + mapOf("format" to JsonPrimitive(3), "extension" to JsonPrimitive(".epub")))
        routes.answer(Chapter, JsonObject(chapter + ("files" to JsonArray(listOf(epubFile)))).toString())
    }

    private fun serve(route: String, type: String, body: ByteArray) = routes.answer(route) {
        MockResponse.Builder().addHeader("Content-Type", type).body(Buffer().write(body)).build()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Fetched(
        val result: ApiResult<Unit>,
        val sink: ByteArrayOutputStream,
        val progress: List<DownloadProgress>,
    )

    private fun fetch(file: FileId): Fetched {
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<DownloadProgress>()
        val result = runBlocking { reader.download(file, audioless = false, sink = sink) { progress += it } }
        return Fetched(result, sink, progress)
    }

    private fun routesAsked(): List<String> = routes.requests().map { it.route() }

    @Test
    fun `an EPUB is streamed from the download route, with its progress`() {
        epub()
        serve(Download, "application/epub+zip", "EPUB-BYTES".toByteArray())

        val fetched = fetch(Album)

        assertThat(fetched.result).isEqualTo(ApiResult.Success(Unit))
        assertThat(fetched.sink.toString()).isEqualTo("EPUB-BYTES")
        assertThat(fetched.progress.last()).isEqualTo(DownloadProgress(10, 10))
        assertThat(routesAsked()).contains("GET /api/Download/chapter")
    }

    @Test
    fun `the download asks for the chapter`() {
        epub()
        serve(Download, "application/epub+zip", "x".toByteArray())

        fetch(Album)

        val asked = routes.requests().single { it.route() == Download }
        assertThat(asked.url.queryParameter("chapterId")).isEqualTo("44")
    }

    @Test
    fun `a zip of several files gives its first EPUB`() {
        epub()
        serve(
            Download,
            "application/zip",
            zipOf("cover.jpg" to "JPG", "Book.epub" to "EPUB-1", "Book-readalong.epub" to "EPUB-2"),
        )

        val fetched = fetch(Album)

        assertThat(fetched.result).isEqualTo(ApiResult.Success(Unit))
        assertThat(fetched.sink.toString()).isEqualTo("EPUB-1")
    }

    @Test
    fun `a zip without an EPUB is unexpected`() {
        epub()
        serve(Download, "application/zip", zipOf("cover.jpg" to "JPG"))

        assertThat(fetch(Album).result).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a PDF is streamed from the PDF route with the key in its address`() {
        routes.answer(Chapter, fixtureBody("chapter-pdf"))
        serve(PdfRoute, "application/pdf", "PDF-BYTES".toByteArray())

        val fetched = fetch(Pdf)

        assertThat(fetched.result).isEqualTo(ApiResult.Success(Unit))
        assertThat(fetched.sink.toString()).isEqualTo("PDF-BYTES")
        val asked = routes.requests().filter { it.route().startsWith("GET /api/Reader/pdf") || it.route() == Download }
        assertThat(asked.map { it.route() }).containsExactly(PdfRoute)
        assertThat(asked.single().url.queryParameter("chapterId")).isEqualTo("1355")
        assertThat(asked.single().url.queryParameter("apiKey")).isNotNull()
    }

    @Test
    fun `a comic is not downloaded`() {
        routes.answer(Chapter, fixtureBody("chapter-asterix"))

        assertThat(fetch(Album).result).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(routesAsked()).doesNotContain(Download)
    }

    @Test
    fun `a refused session is expired and a missing file is not found`() {
        epub()
        routes.fail(Download, 401)
        assertThat(fetch(Album).result).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))

        routes.fail(Download, 404)
        assertThat(fetch(Album).result).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }
}
