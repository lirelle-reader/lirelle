package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

/**
 * What the app asks Komga's list routes (`POST api/v1/books/list`, `POST api/v1/series/list`): a condition, every
 * part of which must hold, and a full-text search; the order is the `sort` query. A book or a series in the trash is
 * never asked for.
 */
class KomgaSearchTest {

    private val notDeleted = """{"deleted":{"operator":"isFalse"}}"""

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun allOf(vararg parts: String) = json("""{"condition":{"allOf":[${parts.joinToString(",")}]}}""")

    @Test
    fun `every book that is not in the trash, unless narrowed`() {
        assertThat(bookSearch(BookListRequest())).isEqualTo(allOf(notDeleted))
    }

    @Test
    fun `each reading state is Komga's read status, several of them any one of them`() {
        val reading = BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Reading)))
        val unreadOrRead =
            BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read, StatusFilter.Unread)))

        assertThat(bookSearch(reading))
            .isEqualTo(allOf(notDeleted, """{"readStatus":{"operator":"is","value":"IN_PROGRESS"}}"""))
        assertThat(bookSearch(unreadOrRead)).isEqualTo(
            allOf(
                notDeleted,
                """{"anyOf":[{"readStatus":{"operator":"is","value":"UNREAD"}},""" +
                    """{"readStatus":{"operator":"is","value":"READ"}}]}""",
            ),
        )
    }

    @Test
    fun `each format is Komga's media profile, comics its pages of images`() {
        val all = BookListRequest(
            filters = BookFilters(formats = setOf(FormatFilter.Comics, FormatFilter.Pdf, FormatFilter.Epub)),
        )

        assertThat(bookSearch(all)).isEqualTo(
            allOf(
                notDeleted,
                """{"anyOf":[{"mediaProfile":{"operator":"is","value":"EPUB"}},""" +
                    """{"mediaProfile":{"operator":"is","value":"PDF"}},""" +
                    """{"mediaProfile":{"operator":"is","value":"DIVINA"}}]}""",
            ),
        )
    }

    @Test
    fun `statuses and formats Komga does not have narrow nothing`() {
        val request = BookListRequest(
            filters = BookFilters(
                formats = setOf(FormatFilter.Audio),
                statuses = setOf(StatusFilter.WantToRead, StatusFilter.Abandoned),
                readAlongOnly = true,
            ),
        )

        assertThat(bookSearch(request)).isEqualTo(allOf(notDeleted))
    }

    @Test
    fun `statuses and formats together must both hold`() {
        val request = BookListRequest(
            filters = BookFilters(formats = setOf(FormatFilter.Pdf), statuses = setOf(StatusFilter.Unread)),
        )

        assertThat(bookSearch(request)).isEqualTo(
            allOf(
                notDeleted,
                """{"readStatus":{"operator":"is","value":"UNREAD"}}""",
                """{"mediaProfile":{"operator":"is","value":"PDF"}}""",
            ),
        )
    }

    @Test
    fun `a text is the full-text search, trimmed, and a blank one is none`() {
        assertThat(bookSearch(BookListRequest(text = "  tide  "))).isEqualTo(
            json("""{"condition":{"allOf":[$notDeleted]},"fullTextSearch":"tide"}"""),
        )
        assertThat(bookSearch(BookListRequest(text = "   "))).isEqualTo(allOf(notDeleted))
    }

    @Test
    fun `books of some series only, as the books of a genre are`() {
        assertThat(bookSearch(BookListRequest(), seriesIds = listOf("0SR1A"))).isEqualTo(
            allOf("""{"seriesId":{"operator":"is","value":"0SR1A"}}""", notDeleted),
        )
        assertThat(bookSearch(BookListRequest(), seriesIds = listOf("0SR1A", "0SR2B"))).isEqualTo(
            allOf(
                """{"anyOf":[{"seriesId":{"operator":"is","value":"0SR1A"}},""" +
                    """{"seriesId":{"operator":"is","value":"0SR2B"}}]}""",
                notDeleted,
            ),
        )
    }

    @Test
    fun `the books of a series are those of its id still in the library`() {
        assertThat(seriesBooksSearch("0SR1A"))
            .isEqualTo(allOf("""{"seriesId":{"operator":"is","value":"0SR1A"}}""", notDeleted))
    }

    @Test
    fun `series not in the trash, narrowed by a text or a genre`() {
        assertThat(seriesSearch(text = null)).isEqualTo(allOf(notDeleted))
        assertThat(seriesSearch(text = " tide ")).isEqualTo(
            json("""{"condition":{"allOf":[$notDeleted]},"fullTextSearch":"tide"}"""),
        )
        assertThat(seriesSearch(text = null, genre = "Drama"))
            .isEqualTo(allOf(notDeleted, """{"genre":{"operator":"is","value":"Drama"}}"""))
    }

    @Test
    fun `each sort is Komga's, a series by its title then its volumes`() {
        assertThat(bookSorts(BookSort.Added, SortDirection.Descending)).containsExactly("createdDate,desc")
        assertThat(bookSorts(BookSort.Title, SortDirection.Ascending)).containsExactly("metadata.title,asc")
        assertThat(bookSorts(BookSort.Series, SortDirection.Ascending))
            .containsExactly("series,asc", "metadata.numberSort,asc").inOrder()
        assertThat(bookSorts(BookSort.Series, SortDirection.Descending))
            .containsExactly("series,desc", "metadata.numberSort,desc").inOrder()
        assertThat(bookSorts(BookSort.Published, SortDirection.Descending))
            .containsExactly("metadata.releaseDate,desc")
        // Not offered for Komga, whose books have no author order: by title rather than an error.
        assertThat(bookSorts(BookSort.Author, SortDirection.Ascending)).containsExactly("metadata.title,asc")
    }

    @Test
    fun `the books being read are those in progress, still in the library`() {
        assertThat(continueSearch())
            .isEqualTo(allOf(notDeleted, """{"readStatus":{"operator":"is","value":"IN_PROGRESS"}}"""))
    }

    @Test
    fun `a Home row asks for twenty books, its See all for fifty`() {
        assertThat(HomeRowExtent.Row.size).isEqualTo(20)
        assertThat(HomeRowExtent.SeeAll.size).isEqualTo(50)
    }

    @Test
    fun `a read list's books are those of its id, narrowed as every list of books`() {
        val request = BookListRequest(
            filters = BookFilters(formats = setOf(FormatFilter.Pdf), statuses = setOf(StatusFilter.Unread)),
            text = "tide",
        )

        assertThat(readListBookSearch("0RL1", BookListRequest()))
            .isEqualTo(allOf("""{"readListId":{"operator":"is","value":"0RL1"}}""", notDeleted))
        assertThat(readListBookSearch("0RL1", request)).isEqualTo(
            json(
                """{"condition":{"allOf":[{"readListId":{"operator":"is","value":"0RL1"}},$notDeleted,""" +
                    """{"readStatus":{"operator":"is","value":"UNREAD"}},""" +
                    """{"mediaProfile":{"operator":"is","value":"PDF"}}]},"fullTextSearch":"tide"}""",
            ),
        )
    }

    @Test
    fun `a read list keeps its own order unless the user chose another sort`() {
        val default = BookListRequest()

        assertThat(readListSorts(default.sort, default.direction)).containsExactly("readList.number,asc")
        // Added, oldest first, is a choice of the user's: the read list's order is not.
        assertThat(readListSorts(BookSort.Added, SortDirection.Ascending)).containsExactly("createdDate,asc")
        assertThat(readListSorts(BookSort.Title, SortDirection.Ascending)).containsExactly("metadata.title,asc")
    }

    @Test
    fun `Lucene's reserved characters and operators are taken as text in every full-text search`() {
        val escaped = """c\\+\\+ \\(vol\\: 1\\) \\\\ a\\/b \\\"x\\\" \\!\\^\\~\\*\\?\\|\\&\\-\\[\\]\\{\\}"""

        assertThat(bookSearch(BookListRequest(text = """c++ (vol: 1) \ a/b "x" !^~*?|&-[]{}"""))).isEqualTo(
            json("""{"condition":{"allOf":[$notDeleted]},"fullTextSearch":"$escaped"}"""),
        )
        assertThat(seriesSearch(text = "war AND peace OR NOT ANDROID")).isEqualTo(
            json("""{"condition":{"allOf":[$notDeleted]},"fullTextSearch":"war and peace or not ANDROID"}"""),
        )
        assertThat(readListBookSearch("0RL1", BookListRequest(text = "a:b"))).isEqualTo(
            json(
                """{"condition":{"allOf":[{"readListId":{"operator":"is","value":"0RL1"}},$notDeleted]},""" +
                    """"fullTextSearch":"a\\:b"}""",
            ),
        )
    }

    private val seinen = ServerLibrary(LibraryId("0DSQ0FDV47DMP"), "Seinen")
    private val inSeinen = """{"libraryId":{"operator":"is","value":"0DSQ0FDV47DMP"}}"""

    @Test
    fun `the books of one library carry its id, after the trash condition`() {
        assertThat(bookSearch(BookListRequest(library = seinen))).isEqualTo(allOf(notDeleted, inSeinen))
    }

    @Test
    fun `a library, a status and a text go together in the books search`() {
        val request = BookListRequest(
            filters = BookFilters(statuses = setOf(StatusFilter.Reading)),
            text = "tide",
            library = seinen,
        )

        assertThat(bookSearch(request)).isEqualTo(
            json(
                """{"condition":{"allOf":[$notDeleted,$inSeinen,""" +
                    """{"readStatus":{"operator":"is","value":"IN_PROGRESS"}}]},"fullTextSearch":"tide"}""",
            ),
        )
    }

    @Test
    fun `the books of a genre's series are narrowed to the library too`() {
        assertThat(bookSearch(BookListRequest(library = seinen), seriesIds = listOf("0SR1A")))
            .isEqualTo(allOf("""{"seriesId":{"operator":"is","value":"0SR1A"}}""", notDeleted, inSeinen))
    }

    @Test
    fun `a search without a library never names one`() {
        assertThat(bookSearch(BookListRequest()).toString()).doesNotContain("libraryId")
        assertThat(seriesSearch(text = "tide", library = null).toString()).doesNotContain("libraryId")
    }

    @Test
    fun `the series of one library carry its id, alone, with a text, and with a genre`() {
        assertThat(seriesSearch(text = null, library = seinen)).isEqualTo(allOf(notDeleted, inSeinen))
        assertThat(seriesSearch(text = "tide", library = seinen)).isEqualTo(
            json("""{"condition":{"allOf":[$notDeleted,$inSeinen]},"fullTextSearch":"tide"}"""),
        )
        assertThat(seriesSearch(text = null, genre = "Seinen", library = seinen)).isEqualTo(
            allOf(notDeleted, """{"genre":{"operator":"is","value":"Seinen"}}""", inSeinen),
        )
    }

    @Test
    fun `a read list's books never carry a library, whatever the request says`() {
        val request = BookListRequest(
            filters = BookFilters(statuses = setOf(StatusFilter.Unread)),
            library = seinen,
        )

        assertThat(readListBookSearch("0RL1", request).toString()).doesNotContain("libraryId")
        assertThat(readListBookSearch("0RL1", request))
            .isEqualTo(readListBookSearch("0RL1", request.copy(library = null)))
    }
}
