package app.tfdn.lirelle.core.library.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The app's rules on what the user chooses for a list of books; what a server makes of them is its source's. */
class BookQueryTest {

    @Test
    fun `filters report whether any is active`() {
        assertThat(BookFilters().isActive).isFalse()
        assertThat(BookFilters(readAlongOnly = true).isActive).isTrue()
        assertThat(BookFilters(formats = setOf(FormatFilter.Pdf)).isActive).isTrue()
        assertThat(BookFilters(statuses = setOf(StatusFilter.Abandoned)).isActive).isTrue()
    }

    @Test
    fun `each sort has a natural first direction`() {
        assertThat(BookSort.Added.defaultDirection).isEqualTo(SortDirection.Descending)
        assertThat(BookSort.Published.defaultDirection).isEqualTo(SortDirection.Descending)
        assertThat(BookSort.Title.defaultDirection).isEqualTo(SortDirection.Ascending)
        assertThat(BookSort.Author.defaultDirection).isEqualTo(SortDirection.Ascending)
        assertThat(BookSort.Series.defaultDirection).isEqualTo(SortDirection.Ascending)
    }

    @Test
    fun `a request starts on the newest books, unfiltered`() {
        val request = BookListRequest()

        assertThat(request.sort).isEqualTo(BookSort.Added)
        assertThat(request.direction).isEqualTo(SortDirection.Descending)
        assertThat(request.filters.isActive).isFalse()
    }
}
