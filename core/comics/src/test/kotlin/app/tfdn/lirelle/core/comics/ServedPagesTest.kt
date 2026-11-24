package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** What the downloads ask of the books served by pages: whether a book is, the count, a page already seen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServedPagesTest {

    private val bench = ComicsBench()
    private val web = bench.web
    private val served: ServedPages = DefaultServedPages(store = bench.store, source = web)

    @After
    fun tearDown() = bench.close()

    @Test
    fun `whether a kind is served by pages is the source's answer`() {
        // A source of the tests serving only comics by pages: the answer cannot come from anywhere else.
        val comicsOnly = object : PageSource by web {
            override fun servesByPages(kind: PagedKind): Boolean = kind == PagedKind.Comic
        }
        val servedByIt: ServedPages = DefaultServedPages(store = bench.store, source = comicsOnly)

        assertThat(servedByIt.servesByPages(PagedKind.Comic)).isTrue()
        assertThat(servedByIt.servesByPages(PagedKind.Djvu)).isFalse()
        assertThat(servedByIt.servesByPages(PagedKind.Pdf)).isFalse()
    }

    @Test
    fun `the count is asked of the source for the book's kind`(): Unit = runBlocking {
        web.pageCount = 8

        assertThat(served.count(PagedKind.Comic, AlbumFileId)).isEqualTo(ApiResult.Success(8))
        assertThat(served.count(PagedKind.Djvu, FileId("9"))).isEqualTo(ApiResult.Success(8))

        assertThat(web.paths).containsExactly("/comic/$AlbumFileId/pages", "/djvu/9/pages").inOrder()
    }

    @Test
    fun `a PDF has no count of pages on the server, and a count that fails says why`(): Unit = runBlocking {
        assertThat(served.count(PagedKind.Pdf, AlbumFileId)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(web.paths).isEmpty()

        web.countFails = true

        val failed = served.count(PagedKind.Comic, AlbumFileId)
        assertThat(failed).isInstanceOf(ApiResult.Failure::class.java)
        assertThat(failed).isNotEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a page the reader has seen is given for its file and version only`(): Unit = runBlocking {
        val pages = bench.open(AlbumFileId, "v1")
        pages.show(2)
        val seen = pages.ready(2).file
        pages.close()

        assertThat(served.cached(AlbumFileId, "v1", 2)).isEqualTo(seen)
        assertThat(served.cached(AlbumFileId, "v1", 2)?.readBytes()).isEqualTo(PortraitPage)
        assertThat(served.cached(AlbumFileId, "v1", 3)).isNull()
        assertThat(served.cached(AlbumFileId, "v2", 2)).isNull()
        assertThat(served.cached(FileId("8"), "v1", 2)).isNull()
    }

    @Test
    fun `asking for a seen page holds nothing, and makes no folder`(): Unit = runBlocking {
        bench.time = 10_000
        val pages = bench.open(AlbumFileId, "v1")
        pages.show(0)
        pages.ready(0)
        pages.close()
        val opened = File(bench.directory, "$AlbumFileId-v1/opened").lastModified()

        assertThat(served.cached(AlbumFileId, "v1", 0)).isNotNull()
        served.cached(FileId("11"), "v1", 0)

        assertThat(bench.albums()).containsExactly("$AlbumFileId-v1")
        assertThat(File(bench.directory, "$AlbumFileId-v1/opened").lastModified()).isEqualTo(opened)
        // Not held: a newer version of the file drops it as it would have.
        bench.open(AlbumFileId, "v2").run {
            show(0)
            ready(0)
            close()
        }
        assertThat(served.cached(AlbumFileId, "v1", 0)).isNull()
    }
}
