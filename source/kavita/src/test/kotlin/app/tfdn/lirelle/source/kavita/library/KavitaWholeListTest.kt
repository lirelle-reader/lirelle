package app.tfdn.lirelle.source.kavita.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Test
import retrofit2.Response

/** A list Kavita gives by pages, asked batch after batch until its end, or a cap. */
class KavitaWholeListTest {

    /** The pages and sizes asked, in order. */
    private val asked = mutableListOf<Pair<Int, Int>>()

    /** A list of [total] items, answered by pages, with its `Pagination` header. */
    private fun pagesOf(total: Int): suspend (Int, Int) -> Response<List<Int>> = { page, size ->
        asked += page to size
        val from = (page - 1) * size
        val items = (from until minOf(from + size, total)).toList()
        Response.success(items, headersOf("Pagination", """{"totalItems":$total}"""))
    }

    @Test
    fun `batches of 100 are asked until a short one`() = runBlocking {
        val all = wholeList(fetch = pagesOf(250))

        assertThat(all).hasSize(250)
        assertThat(asked).containsExactly(1 to 100, 2 to 100, 3 to 100).inOrder()
    }

    @Test
    fun `a batch size of its own is asked`() = runBlocking {
        val all = wholeList(batchSize = 1000, fetch = pagesOf(1500))

        assertThat(all).hasSize(1500)
        assertThat(asked).containsExactly(1 to 1000, 2 to 1000).inOrder()
    }

    @Test
    fun `no more batches than the cap are asked`() = runBlocking {
        val all = wholeList(batchSize = 10, maxBatches = 3, fetch = pagesOf(100))

        assertThat(all).hasSize(30)
        assertThat(asked.map { it.first }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `the default cap is 50 batches`() = runBlocking {
        wholeList(fetch = pagesOf(10_000))

        assertThat(asked).hasSize(50)
    }
}
