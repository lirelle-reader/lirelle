package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaProgressDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMetadataDto
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItems
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What narrows a library's whole list on the device, and how it is sorted there: statuses from the account's
 * progress, formats from the items, a text against the names a book is known by, case and accents ignored; the four
 * sorts as the server sorts its pages.
 */
class AbsBookFiltersTest {

    private val readings = AbsReadings.of(
        listOf(
            AbsMediaProgressDto(libraryItemId = "reading", progress = 0.4),
            AbsMediaProgressDto(libraryItemId = "read", progress = 1.0, isFinished = true),
        ),
    )

    private fun kept(filters: BookFilters = BookFilters(), text: String? = null, genre: String? = null) =
        AbsBookFilters.of(BookListRequest(filters = filters, text = text, genre = genre))

    @Test
    fun `no record is unread, a record not finished reading, a finished one read, and several statuses add up`() {
        val items = listOf(item("unread"), item("reading"), item("read"))
        fun ids(vararg statuses: StatusFilter) =
            items.filter { kept(BookFilters(statuses = statuses.toSet())).keeps(it, readings) }.map { it.id }

        assertThat(ids(StatusFilter.Unread)).containsExactly("unread")
        assertThat(ids(StatusFilter.Reading)).containsExactly("reading")
        assertThat(ids(StatusFilter.Read)).containsExactly("read")
        assertThat(ids(StatusFilter.Unread, StatusFilter.Read)).containsExactly("unread", "read").inOrder()
    }

    @Test
    fun `want to read, abandoned and read-along are not kept by Audiobookshelf, and narrow nothing`() {
        val unkept = setOf(StatusFilter.WantToRead, StatusFilter.Abandoned)
        val filters = BookFilters(statuses = unkept, readAlongOnly = true)

        assertThat(kept(filters).isActive).isFalse()
        assertThat(kept(filters).keeps(item("read"), readings)).isTrue()
        val withRead = kept(BookFilters(statuses = unkept + StatusFilter.Read))
        assertThat(withRead.keeps(item("unread"), readings)).isFalse()
        assertThat(withRead.keeps(item("read"), readings)).isTrue()
    }

    @Test
    fun `formats are the items', and several add up`() {
        val items = recordedItems("items-livres-all")
        fun count(vararg formats: FormatFilter) =
            items.count { kept(BookFilters(formats = formats.toSet())).keeps(it, AbsReadings.None) }

        assertThat(count(FormatFilter.Pdf)).isEqualTo(2)
        assertThat(count(FormatFilter.Audio)).isEqualTo(19)
        assertThat(count(FormatFilter.Audio, FormatFilter.Pdf)).isEqualTo(21)
        assertThat(count(FormatFilter.Comics)).isEqualTo(0)
    }

    @Test
    fun `a text is looked for in the title, subtitle, authors, narrators and series, case and accents ignored`() {
        val book = item(
            "b",
            metadata = AbsMetadataDto(
                title = "Les Réformateurs",
                subtitle = "Une odyssée",
                authorName = "Antoine de Saint-Exupéry",
                narratorName = "Benjamin Jungers",
                seriesName = "Harry Potter 7 3/4 #3",
            ),
        )

        listOf("REFORMATEURS", "odyssee", "saint-exupéry", "SAINT-EXUPERY", "jungers", "potter 7").forEach { text ->
            assertThat(kept(text = text).keeps(book, AbsReadings.None)).isTrue()
        }
        assertThat(kept(text = "voyageur").keeps(book, AbsReadings.None)).isFalse()
        assertThat(kept(text = "   ").isActive).isFalse()
        assertThat(kept(text = " jungers ").isActive).isTrue()
    }

    @Test
    fun `a genre alone narrows nothing on the device, with a filter it is the book's genre exactly`() {
        val humour = item("h", metadata = AbsMetadataDto(genres = listOf("Humour", "Science-fiction")))

        assertThat(kept(genre = "Humour").isActive).isFalse()
        val audio = BookFilters(formats = setOf(FormatFilter.Audio))
        assertThat(kept(audio, genre = "Humour").keeps(humour, AbsReadings.None)).isTrue()
        assertThat(kept(audio, genre = "Humo").keeps(humour, AbsReadings.None)).isFalse()
    }

    @Test
    fun `by title as a reader sorts them, by author by last name, by date added`() {
        val items = listOf(
            item("1", addedAt = 3, AbsMetadataDto(title = "fondation", authorNameLF = "Asimov, Isaac")),
            item("2", addedAt = 1, AbsMetadataDto(title = "Économie", authorNameLF = "Smith, Adam")),
            item("3", addedAt = 2, AbsMetadataDto(title = "Dune", authorNameLF = "Herbert, Frank")),
        )
        fun ids(sort: BookSort, direction: SortDirection) = AbsBookSorts.sorted(items, sort, direction).map { it.id }

        assertThat(ids(BookSort.Title, SortDirection.Ascending)).containsExactly("3", "2", "1").inOrder()
        assertThat(ids(BookSort.Title, SortDirection.Descending)).containsExactly("1", "2", "3").inOrder()
        assertThat(ids(BookSort.Author, SortDirection.Ascending)).containsExactly("1", "3", "2").inOrder()
        assertThat(ids(BookSort.Added, SortDirection.Descending)).containsExactly("1", "3", "2").inOrder()
        assertThat(ids(BookSort.Series, SortDirection.Descending)).containsExactly("1", "3", "2").inOrder()
    }

    @Test
    fun `by date published, books without a year come last either way, by title`() {
        val items = listOf(
            item("none"),
            item("1979", metadata = AbsMetadataDto(publishedYear = "1979-10-12")),
            item("1992", metadata = AbsMetadataDto(publishedYear = "1992")),
            item("blank", metadata = AbsMetadataDto(publishedYear = " ")),
        )
        fun ids(direction: SortDirection) = AbsBookSorts.sorted(items, BookSort.Published, direction).map { it.id }

        assertThat(ids(SortDirection.Ascending)).containsExactly("1979", "1992", "blank", "none").inOrder()
        assertThat(ids(SortDirection.Descending)).containsExactly("1992", "1979", "blank", "none").inOrder()
    }

    @Test
    fun `the server is asked each sort by its field`() {
        assertThat(BookSort.entries.map(AbsBookSorts::serverField)).containsExactly(
            "addedAt",
            "media.metadata.title",
            "media.metadata.authorNameLF",
            "addedAt",
            "media.metadata.publishedYear",
        ).inOrder()
    }
}

/** A list's item [id] that has audio, added at [addedAt], told by [metadata]. */
private fun item(id: String, addedAt: Long? = null, metadata: AbsMetadataDto = AbsMetadataDto(title = id)) =
    AbsItemDto(id = id, addedAt = addedAt, media = AbsMediaDto(metadata = metadata, numTracks = 1))
