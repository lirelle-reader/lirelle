package app.tfdn.lirelle.feature.reader.web

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.reader.archive.EpubArchive
import app.tfdn.lirelle.feature.reader.BookContent
import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

private const val Chapter = "<html><body>one</body></html>"

@RunWith(AndroidJUnit4::class)
class BookPathHandlerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val books = BookServer()
    private val handler = BookPathHandler(books)

    private fun serve(vararg more: Pair<String, String>): String {
        val archive = testArchive(
            folder.newFile(),
            "mimetype" to "application/epub+zip",
            "OEBPS/text/ch1.xhtml" to Chapter,
            "OEBPS/style.css" to "p { margin: 0 }",
            *more,
        )
        books.serve(BookContent.Epub(archive))
        return checkNotNull(books.base.value).removePrefix("/book/")
    }

    @Test
    fun `the index lists the entries and their sizes`() {
        val token = serve()

        val response = handler.handle(token + "index.json")

        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.mimeType).isEqualTo("application/json")
        assertThat(Json.parseToJsonElement(response.text())).isEqualTo(
            Json.parseToJsonElement(
                """{"entries":{"mimetype":20,"OEBPS/text/ch1.xhtml":${Chapter.length},"OEBPS/style.css":15}}""",
            ),
        )
    }

    @Test
    fun `an entry is served with its media type`() {
        val token = serve("OEBPS/cover.png" to "png")

        val chapter = handler.handle(token + "OEBPS/text/ch1.xhtml")

        assertThat(chapter.statusCode).isEqualTo(200)
        assertThat(chapter.mimeType).isEqualTo("application/xhtml+xml")
        assertThat(chapter.text()).isEqualTo(Chapter)
        assertThat(handler.handle(token + "OEBPS/style.css").mimeType).isEqualTo("text/css")
        assertThat(handler.handle(token + "OEBPS/cover.png").mimeType).isEqualTo("image/png")
        assertThat(handler.handle(token + "mimetype").mimeType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `an entry larger than the archive lets through is not found`() {
        val file = folder.newFile()
        testArchive(file, "OEBPS/small.bin" to "1234", "OEBPS/large.bin" to "12345").close()
        books.serve(BookContent.Epub(EpubArchive(file, maxEntryBytes = 4)))
        val token = checkNotNull(books.base.value).removePrefix("/book/")

        assertThat(handler.handle(token + "OEBPS/small.bin").text()).isEqualTo("1234")
        assertThat(handler.handle(token + "OEBPS/large.bin").statusCode).isEqualTo(404)
    }

    @Test
    fun `the path is the entry's exact name, taken as it comes`() {
        val token = serve("OEBPS/a b#c?d%20e.xhtml" to "odd")

        assertThat(handler.handle(token + "OEBPS/a b#c?d%20e.xhtml").text()).isEqualTo("odd")
        assertThat(handler.handle(token + "OEBPS/a b#c?d e.xhtml").statusCode).isEqualTo(404)
    }

    @Test
    fun `an unknown entry is a 404 with an empty body`() {
        val token = serve()

        listOf("OEBPS/missing.xhtml", "OEBPS/", "OEBPS", "", "oebps/style.css").forEach {
            val response = handler.handle(token + it)

            assertThat(response.statusCode).isEqualTo(404)
            assertThat(response.text()).isEmpty()
        }
    }

    @Test
    fun `a path that leaves the archive is a 404`() {
        val token = serve()
        folder.newFile("secret.txt").writeText("outside")

        listOf("../secret.txt", "OEBPS/../../secret.txt", "..\\secret.txt", "/etc/hosts", "OEBPS//style.css")
            .forEach { assertThat(handler.handle(token + it).statusCode).isEqualTo(404) }
    }

    @Test
    fun `another token, or none, is a 404`() {
        val token = serve()

        assertThat(handler.handle("0".repeat(token.length - 1) + "/index.json").statusCode).isEqualTo(404)
        assertThat(handler.handle("index.json").statusCode).isEqualTo(404)
        assertThat(handler.handle("/index.json").statusCode).isEqualTo(404)
        assertThat(handler.handle(token.dropLast(1)).statusCode).isEqualTo(404)
    }

    @Test
    fun `each served book gets a new address that cannot be guessed`() {
        val first = serve()
        val second = serve()

        assertThat(first).matches("[0-9a-f]{64}/")
        assertThat(second).matches("[0-9a-f]{64}/")
        assertThat(second).isNotEqualTo(first)
        assertThat(handler.handle(first + "index.json").statusCode).isEqualTo(404)
        assertThat(handler.handle(second + "index.json").statusCode).isEqualTo(200)
    }

    @Test
    fun `nothing is served once the book is withdrawn or closed`() {
        val token = serve()
        val archive = (checkNotNull(books.current()).content as BookContent.Epub).archive

        archive.close()
        assertThat(handler.handle(token + "OEBPS/style.css").statusCode).isEqualTo(404)

        books.serve(null)
        assertThat(books.base.value).isNull()
        assertThat(handler.handle(token + "index.json").statusCode).isEqualTo(404)
    }

    private fun serveWhole(text: String): Pair<String, File> {
        val file = folder.newFile().apply { writeText(text) }
        books.serve(BookContent.Whole(file, BookFormat.Mobi))
        return checkNotNull(books.base.value).removePrefix("/book/") to file
    }

    @Test
    fun `a book that is one file is served whole under its address, as bytes`() {
        val (token, _) = serveWhole("BOOKMOBI and the rest")

        val response = handler.handle(token + "file")

        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.mimeType).isEqualTo("application/octet-stream")
        assertThat(response.encoding).isNull()
        assertThat(response.text()).isEqualTo("BOOKMOBI and the rest")
        assertThat(response.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
    }

    @Test
    fun `a book that is one file has no index and no entry`() {
        val (token, file) = serveWhole("BOOKMOBI")

        listOf("index.json", "", "file/", "file/more", "File", file.name, "../${file.name}").forEach {
            assertThat(handler.handle(token + it).statusCode).isEqualTo(404)
        }
        assertThat(handler.handle("0".repeat(token.length - 1) + "/file").statusCode).isEqualTo(404)
    }

    @Test
    fun `a file that has gone from the disk is not found, and an EPUB has no whole file`() {
        val (token, file) = serveWhole("BOOKMOBI")
        file.delete()

        assertThat(handler.handle(token + "file").statusCode).isEqualTo(404)
        assertThat(handler.handle(serve() + "file").statusCode).isEqualTo(404)
    }

    @Test
    fun `every response carries the content security policy`() {
        val token = serve()

        listOf("index.json", "OEBPS/style.css", "OEBPS/missing.css").forEach {
            val response = handler.handle(token + it)

            assertThat(response.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderAssetsTest {

    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
    private val handler = AssetFolderHandler(assets, "reader")

    @Test
    fun `the policy sent as a header is the one the page declares`() {
        val page = assets.open("reader/index.html").use { it.readBytes().decodeToString() }
        val declared = Regex("""http-equiv="Content-Security-Policy" content="([^"]+)"""").find(page)!!.groupValues[1]

        assertThat(ContentSecurityPolicy).isEqualTo(declared)
    }

    @Test
    fun `the page and its modules are served with their media types`() {
        val page = handler.handle("index.html")
        val script = handler.handle("reader.js")
        val engine = handler.handle("foliate/view.js")

        assertThat(page.statusCode).isEqualTo(200)
        assertThat(page.mimeType).isEqualTo("text/html")
        assertThat(page.encoding).isEqualTo("utf-8")
        assertThat(page.text()).contains("<foliate-view")
        assertThat(script.mimeType).isEqualTo("text/javascript")
        assertThat(script.text()).contains("window.lirelleHost.postMessage")
        assertThat(engine.statusCode).isEqualTo(200)
        assertThat(engine.mimeType).isEqualTo("text/javascript")
        listOf(page, script, engine).forEach {
            assertThat(it.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
        }
    }

    @Test
    fun `media types follow the extension`() {
        assertThat(assetMediaType("fonts/LirelleSerif-Regular.woff2")).isEqualTo("font/woff2")
        assertThat(assetMediaType("a/b.CSS")).isEqualTo("text/css")
        assertThat(assetMediaType("data.json")).isEqualTo("application/json")
        assertThat(assetMediaType("foliate/LICENSE")).isEqualTo("application/octet-stream")
        assertThat(assetMediaType("odd.name/file")).isEqualTo("application/octet-stream")
    }

    @Test
    fun `what the folder does not hold is a 404, and so is a path that leaves it`() {
        val fonts = AssetFolderHandler(assets, "fonts")
        val responses = listOf(
            handler.handle("missing.js"),
            handler.handle(""),
            handler.handle("foliate"),
            handler.handle("../reader/index.html"),
            handler.handle("foliate/../index.html"),
            handler.handle("foliate//view.js"),
            handler.handle("foliate\\view.js"),
            fonts.handle("LirelleNowhere-Regular.woff2"),
            fonts.handle("../reader/index.html"),
        )

        responses.forEach {
            assertThat(it.statusCode).isEqualTo(404)
            assertThat(it.text()).isEmpty()
            assertThat(it.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
        }
    }
}
