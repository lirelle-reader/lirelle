package app.tfdn.lirelle.source.kavita.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.BrowsePersonDto
import app.tfdn.lirelle.source.kavita.library.dto.PersonDto
import app.tfdn.lirelle.source.kavita.network.answer
import java.text.Collator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException

/**
 * The authors of the profile's server, asked of [server] (spec §4 and §14): the people named Writer or Penciller on
 * a chapter, Kavita having no Artist role. A Kavita person is unique by name, and its name is the author's id: an
 * author is asked by name. Kavita counts the chapters a person is named on, which stand for their books. Kavita
 * cannot list a library's people: the library chosen does not narrow the authors.
 */
internal class KavitaAuthors(private val server: KavitaProfileServer) {

    private val calls = KavitaCallLimit()

    /** The writers and pencillers in [sort]'s order, narrowed to the names holding [text] when given, by pages. */
    fun authors(sort: AuthorSort, direction: SortDirection, text: String?): PagedList<Author> {
        val filter = personFilter(
            listOfNotNull(
                Statement(PersonField.Role, Comparison.Contains, WriterAndPenciller),
                text.orNullIfBlank()?.let { Statement(PersonField.Name, Comparison.Matches, it) },
            ),
            sortField = if (sort == AuthorSort.BookCount) PersonSortField.ChapterCount else PersonSortField.Name,
            ascending = direction == SortDirection.Ascending,
        )
        val total = MutableStateFlow<Int?>(null)
        val pager = Pager(PagingConfig(PageSize, initialLoadSize = PageSize, enablePlaceholders = true)) {
            IndexPagingSource(PageSize, onTotal = { total.value = it }) { page, size ->
                server.loaded { remote ->
                    val response = remote.api.persons(filter, page + FirstPage, size)
                    val people = response.answer()
                    // Without a total, a full page says that there is at least one more.
                    val counted = page * size + people.size + if (people.size >= size) 1 else 0
                    PageResult(people.map { it.author() }, response.totalItems() ?: counted)
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    /** The author of that name: the person's description is their biography, the chapters they are on their books. */
    suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> {
        val name = authorId.value.orNullIfBlank() ?: return NotFound
        return server.call { remote ->
            coroutineScope {
                val counted = async {
                    val filter = personFilter(listOf(Statement(PersonField.Name, Comparison.Equal, name)))
                    remote.api.persons(filter, FirstPage, CountPageSize).answer()
                }
                val person = remote.person(name)
                val bookCount = counted.await().firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                    ?.chapterCount ?: 0
                val author = Author(AuthorId(name), person.name.orNullIfBlank() ?: name, bookCount, portraitUrl = null)
                AuthorDetail(author, biography = person.description.orNullIfBlank())
            }
        }
    }

    /**
     * The books of the author of that name: the series they wrote or drew, each unfolded into its books, kept to
     * those whose writers or pencillers name them; the series by name as a reader sorts them, each in its order.
     */
    suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> {
        val name = authorId.value.orNullIfBlank() ?: return NotFound
        return server.call { remote ->
            val personId = remote.person(name).id
            val ofPerson = personId.toString()
            val filter = seriesFilter(
                listOf(
                    Statement(SeriesField.Writers, Comparison.Contains, ofPerson),
                    Statement(SeriesField.Penciller, Comparison.Contains, ofPerson),
                ),
                or = true,
            )
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            val series = wholeList { page, size -> calls.limited { remote.api.series(filter, page, size) } }
                // A series added on the server between two batches shifts the list: one comes twice.
                .distinctBy { it.id }
                .sortedWith(compareBy(collator) { it.sortName.orNullIfBlank() ?: it.name })
            calls.unfold(remote, series) { book ->
                book.chapter.writers.any { it.id == personId } || book.chapter.pencillers.any { it.id == personId }
            }
        }
    }
}

/**
 * The person of that name; not found when the server answers 404 (a name it does not know, or does not show this
 * account), and, as a guard, when it answers with no content.
 */
private suspend fun KavitaRemote.person(name: String): PersonDto {
    val response = api.person(name)
    if (!response.isSuccessful) throw HttpException(response)
    return response.body() ?: notFound()
}

/** A person as an author, its name standing for its id. Kavita keeps no person without a name. */
private fun BrowsePersonDto.author(): Author {
    val shown = name.trim()
    return Author(AuthorId(shown), shown, chapterCount, portraitUrl = null)
}

/** How many authors a page of the list holds. */
private const val PageSize = 60

/** How many people of one name are asked for to count an author's books: Kavita's names are unique. */
private const val CountPageSize = 1
