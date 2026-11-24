package app.tfdn.lirelle.core.library

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IndexPagingSourceTest {

    private val all = (1..7).map { "item-$it" }
    private val requested = mutableListOf<Int>()
    private val totals = mutableListOf<Int>()
    private var failOnPage: Int? = null

    private fun source() = IndexPagingSource(pageSize = 3, onTotal = { totals += it }) { page, size ->
        requested += page
        if (page == failOnPage) throw IOException("offline")
        PageResult(all.drop(page * size).take(size), all.size)
    }

    private fun pager() = TestPager(PagingConfig(pageSize = 3, initialLoadSize = 3), source())

    @Test
    fun `the first load is page zero and reports what follows`() = runTest {
        val page = pager().refresh() as LoadResult.Page

        assertThat(page.data).containsExactly("item-1", "item-2", "item-3").inOrder()
        assertThat(page.prevKey).isNull()
        assertThat(page.nextKey).isEqualTo(1)
        assertThat(page.itemsBefore).isEqualTo(0)
        assertThat(page.itemsAfter).isEqualTo(4)
        assertThat(totals).containsExactly(7)
    }

    @Test
    fun `appending walks the pages to the end`() = runTest {
        val pager = pager()
        pager.refresh()

        val second = pager.append() as LoadResult.Page
        val third = pager.append() as LoadResult.Page

        assertThat(second.data).containsExactly("item-4", "item-5", "item-6").inOrder()
        assertThat(third.data).containsExactly("item-7")
        assertThat(third.nextKey).isNull()
        assertThat(third.itemsAfter).isEqualTo(0)
        assertThat(requested).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `an empty result ends the list`() = runTest {
        val empty = IndexPagingSource<String>(pageSize = 3) { _, _ -> PageResult(emptyList(), 0) }

        val page = TestPager(PagingConfig(pageSize = 3), empty).refresh() as LoadResult.Page

        assertThat(page.data).isEmpty()
        assertThat(page.nextKey).isNull()
    }

    @Test
    fun `a page shorter than asked for ends the list, whatever the total says`() = runTest {
        val source = IndexPagingSource(pageSize = 3) { _, _ -> PageResult(listOf("a", "b"), 40) }

        val page = TestPager(PagingConfig(pageSize = 3), source).refresh() as LoadResult.Page

        assertThat(page.itemsAfter).isEqualTo(0)
        assertThat(page.nextKey).isNull()
    }

    @Test
    fun `a network failure is a load error, and the page can be retried`() = runTest {
        val pager = pager()
        pager.refresh()
        failOnPage = 1

        assertThat(pager.append()).isInstanceOf(LoadResult.Error::class.java)

        failOnPage = null
        assertThat((pager.append() as LoadResult.Page).data).contains("item-4")
    }

    @Test
    fun `a failure the source named is a load error that carries it`() = runTest {
        val failure = ApiErrorException(ApiError.ServerFailed)
        val source = IndexPagingSource<String>(pageSize = 3) { _, _ -> throw failure }

        val result = TestPager(PagingConfig(pageSize = 3), source).refresh()

        assertThat((result as LoadResult.Error).throwable).isSameInstanceAs(failure)
    }

    @Test
    fun `a total that shrank does not produce a negative count`() = runTest {
        val source = IndexPagingSource(pageSize = 3) { _, _ -> PageResult(listOf("a", "b", "c"), 2) }

        val page = TestPager(PagingConfig(pageSize = 3), source).refresh() as LoadResult.Page

        assertThat(page.itemsAfter).isEqualTo(0)
        assertThat(page.nextKey).isNull()
    }
}
