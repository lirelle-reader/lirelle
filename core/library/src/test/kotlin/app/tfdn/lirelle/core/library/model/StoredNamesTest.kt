package app.tfdn.lirelle.core.library.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The names of the app's choices that are written down and read back: a list's chosen sort (library preferences)
 * and a Home row's "See all" route (the back stack). A version that renamed one would lose what the previous one
 * wrote.
 */
class StoredNamesTest {

    @Test
    fun `the sorts of books stored by the previous versions read back as the same sorts`() {
        val stored = listOf("Added", "Title", "Author", "Series", "Published")

        assertThat(stored.map { BookSort.valueOf(it) })
            .containsExactly(BookSort.Added, BookSort.Title, BookSort.Author, BookSort.Series, BookSort.Published)
            .inOrder()
        assertThat(BookSort.entries.map { it.name }).containsExactlyElementsIn(stored)
    }

    @Test
    fun `the sorts of authors stored by the previous versions read back as the same sorts`() {
        val stored = listOf("Name", "BookCount")

        assertThat(stored.map { AuthorSort.valueOf(it) }).containsExactly(AuthorSort.Name, AuthorSort.BookCount)
            .inOrder()
        assertThat(AuthorSort.entries.map { it.name }).containsExactlyElementsIn(stored)
    }

    @Test
    fun `the Home rows named in a route by the previous versions read back as the same rows`() {
        val stored = listOf("Continue", "WantToRead", "UpNext", "RecentlyAdded")

        assertThat(stored.map { HomeRowKind.valueOf(it) }).containsExactly(
            HomeRowKind.Continue,
            HomeRowKind.WantToRead,
            HomeRowKind.UpNext,
            HomeRowKind.RecentlyAdded,
        ).inOrder()
        assertThat(HomeRowKind.entries.map { it.name }).containsExactlyElementsIn(stored).inOrder()
    }

    // The filters are not stored today; their names stay those of the previous versions all the same.
    @Test
    fun `the filters keep the names of the previous versions`() {
        assertThat(FormatFilter.entries.map { it.name }).containsExactly("Epub", "Pdf", "Comics", "Audio").inOrder()
        assertThat(StatusFilter.entries.map { it.name })
            .containsExactly("Unread", "WantToRead", "Reading", "Read", "Abandoned").inOrder()
    }
}
