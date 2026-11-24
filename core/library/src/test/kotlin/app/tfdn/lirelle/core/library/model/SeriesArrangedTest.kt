package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class SeriesArrangedTest {

    private fun series(name: String, books: Int, read: Int, added: String? = null) = Series(
        id = SeriesId(name),
        name = name,
        authors = emptyList(),
        bookCount = books,
        readCount = read,
        coverUrls = emptyList(),
        lastAddedAt = added?.let(Instant::parse),
    )

    private val dawn = series("Dawn", books = 6, read = 6, added = "2026-01-01T00:00:00Z")
    private val elan = series("Élan", books = 2, read = 0, added = "2026-03-01T00:00:00Z")
    private val forge = series("Forge", books = 7, read = 3, added = "2026-02-01T00:00:00Z")
    private val alpha = series("alpha", books = 7, read = 7)
    private val all = listOf(forge, dawn, alpha, elan)

    private fun names(sort: SeriesSort, direction: SortDirection, filter: SeriesProgressFilter? = null) =
        all.arranged(sort, direction, filter).map { it.name }

    @Test
    fun `by name ignores case and accents`() {
        assertThat(names(SeriesSort.Name, SortDirection.Ascending))
            .containsExactly("alpha", "Dawn", "Élan", "Forge").inOrder()
        assertThat(names(SeriesSort.Name, SortDirection.Descending))
            .containsExactly("Forge", "Élan", "Dawn", "alpha").inOrder()
    }

    @Test
    fun `by number of books, ties broken by name`() {
        assertThat(names(SeriesSort.BookCount, SortDirection.Descending))
            .containsExactly("alpha", "Forge", "Dawn", "Élan").inOrder()
        assertThat(names(SeriesSort.BookCount, SortDirection.Ascending))
            .containsExactly("Élan", "Dawn", "alpha", "Forge").inOrder()
    }

    @Test
    fun `by number of unread books`() {
        // unread: Forge 4, Élan 2, alpha 0, Dawn 0
        assertThat(names(SeriesSort.UnreadCount, SortDirection.Descending))
            .containsExactly("Forge", "Élan", "alpha", "Dawn").inOrder()
        assertThat(names(SeriesSort.UnreadCount, SortDirection.Ascending))
            .containsExactly("alpha", "Dawn", "Élan", "Forge").inOrder()
    }

    @Test
    fun `by most recently added, series without a date last`() {
        assertThat(names(SeriesSort.RecentlyAdded, SortDirection.Descending))
            .containsExactly("Élan", "Forge", "Dawn", "alpha").inOrder()
    }

    @Test
    fun `by reading progress`() {
        // progress: alpha 100 %, Dawn 100 %, Forge 43 %, Élan 0 %
        assertThat(names(SeriesSort.Progress, SortDirection.Descending))
            .containsExactly("alpha", "Dawn", "Forge", "Élan").inOrder()
    }

    @Test
    fun `progress filter`() {
        assertThat(names(SeriesSort.Name, SortDirection.Ascending, SeriesProgressFilter.NotStarted))
            .containsExactly("Élan")
        assertThat(names(SeriesSort.Name, SortDirection.Ascending, SeriesProgressFilter.InProgress))
            .containsExactly("Forge")
        assertThat(names(SeriesSort.Name, SortDirection.Ascending, SeriesProgressFilter.Complete))
            .containsExactly("alpha", "Dawn").inOrder()
    }

    @Test
    fun `unread count and progress never go out of range`() {
        val odd = series("Odd", books = 2, read = 5)
        assertThat(odd.unreadCount).isEqualTo(0)
        assertThat(odd.progress).isEqualTo(1f)
        assertThat(series("Empty", books = 0, read = 0).progress).isEqualTo(0f)
    }
}
