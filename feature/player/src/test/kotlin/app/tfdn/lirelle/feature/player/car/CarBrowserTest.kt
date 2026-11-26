package app.tfdn.lirelle.feature.player.car

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith

/** The car's browser: each question of a car answered from the profile's library, as the items Media3 hands it. */
@RunWith(AndroidJUnit4::class)
class CarBrowserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shelves = FakeCarShelves()
    private val link = FakeCarLink()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var closed = false
    private val texts = CarTexts(context.resources)
    private val browser = CarBrowser(
        shelves,
        CarItems(context.resources, "app.tfdn.lirelle.covers", "profile-1"),
        NoticeAsLine(texts),
        scope,
    ) { closed }

    private val road = CarNode.Book(BookId("1"), "The Long Road", CarLine.Author("Ann Author"))
    private val river = CarNode.Book(BookId("2"), "The Wide River", null)
    private val saga = CarNode.Series(SeriesId("4"), "A Saga", 2)

    /** The fakes answer at once: so does the browser. */
    private fun <T> ListenableFuture<LibraryResult<T>>.now(): LibraryResult<T> {
        assertThat(isDone).isTrue()
        return get()
    }

    private fun LibraryResult<ImmutableList<MediaItem>>.ids(): List<String> = checkNotNull(value).map { it.mediaId }

    // --- the root

    @Test
    fun `the root is given with the content styles the car reads`() {
        val root = browser.root().now()

        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value?.mediaId).isEqualTo("root")
        assertThat(checkNotNull(root.params).extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    // --- children

    @Test
    fun `the root lists what the library says it has, as items`() {
        shelves.places[CarPlace.Root] = CarAnswer.Nodes(CarPlace.Tab.entries.map(CarNode::Tab))

        val tabs = browser.children(link, "root", 0, 100).now()

        assertThat(tabs.ids()).containsExactly("tab/home", "tab/downloaded", "tab/audiobooks", "tab/series").inOrder()
        assertThat(checkNotNull(tabs.value).first().mediaMetadata.title.toString()).isEqualTo("Home")
    }

    @Test
    fun `a place is asked the page the car wants`() {
        shelves.places[CarPlace.Tab.Audiobooks] = CarAnswer.Nodes(listOf(river))

        val page = browser.children(link, "tab/audiobooks", 3, 25).now()

        assertThat(page.ids()).containsExactly("book/2")
        assertThat(shelves.asked).containsExactly(Triple(CarPlace.Tab.Audiobooks, 3, 25))
    }

    @Test
    fun `a series lists its books`() {
        shelves.places[CarPlace.OneSeries(SeriesId("4"))] = CarAnswer.Nodes(listOf(road, river))

        assertThat(browser.children(link, "series/4", 0, 100).now().ids()).containsExactly("book/1", "book/2").inOrder()
    }

    @Test
    fun `a place with nothing is an empty list, which the car words by itself`() {
        val empty = browser.children(link, "tab/downloaded", 0, 100).now()

        assertThat(empty.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(empty.value).isEmpty()
        assertThat(link.said).isEmpty()
    }

    @Test
    fun `a place that says why it lists nothing is answered a line that says it`() {
        shelves.places[CarPlace.Tab.Home] = CarAnswer.Notice(CarNotice.Unreachable)

        val line = browser.children(link, "tab/home", 0, 100).now()

        assertThat(line.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(checkNotNull(line.value).single().mediaMetadata.title.toString())
            .isEqualTo("The server cannot be reached.")
        assertThat(link.said).isEmpty()
    }

    @Test
    fun `why a place or a search lists nothing is said once - the pages after the first are empty`() {
        shelves.places[CarPlace.Tab.Audiobooks] = CarAnswer.Notice(CarNotice.SignInNeeded)
        shelves.searched = CarAnswer.Notice(CarNotice.Unreachable)

        assertThat(browser.children(link, "tab/audiobooks", 0, 25).now().value).hasSize(1)
        assertThat(browser.children(link, "tab/audiobooks", 1, 25).now().value).isEmpty()
        assertThat(browser.searchResult(link, "road", 0, 25).now().value).hasSize(1)
        assertThat(browser.searchResult(link, "road", 2, 25).now().value).isEmpty()
    }

    @Test
    fun `an id that names no place is refused, and the library is asked nothing`() {
        listOf("elsewhere", "book/1", "resume/1", "").forEach {
            assertThat(browser.children(link, it, 0, 100).now().resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        }
        assertThat(shelves.asked).isEmpty()
    }

    // --- one item

    @Test
    fun `the root and a tab are given by their ids`() {
        assertThat(browser.item("root").now().value?.mediaMetadata?.isBrowsable).isTrue()
        val tab = browser.item("tab/series").now()

        assertThat(tab.value?.mediaId).isEqualTo("tab/series")
        assertThat(tab.value?.mediaMetadata?.title.toString()).isEqualTo("Series")
    }

    @Test
    fun `a book or a series the car was listed is given again by its id`() {
        shelves.places[CarPlace.Tab.Series] = CarAnswer.Nodes(listOf(saga))
        shelves.places[CarPlace.OneSeries(SeriesId("4"))] = CarAnswer.Nodes(listOf(road))
        browser.children(link, "tab/series", 0, 100).now()
        browser.children(link, "series/4", 0, 100).now()

        assertThat(browser.item("series/4").now().value?.mediaMetadata?.title.toString()).isEqualTo("A Saga")
        assertThat(browser.item("book/1").now().value?.mediaMetadata?.title.toString()).isEqualTo("The Long Road")
    }

    @Test
    fun `a book found by a search is given again by its id`() {
        shelves.searched = CarAnswer.Nodes(listOf(road))
        browser.search(link, "road", null).now()

        assertThat(browser.item("book/1").now().value?.mediaId).isEqualTo("book/1")
    }

    @Test
    fun `an item the car was never listed, or of elsewhere, is refused`() {
        listOf("book/9", "series/9", "resume/1", "").forEach {
            assertThat(browser.item(it).now().resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        }
    }

    // --- search

    @Test
    fun `a search is run, kept, and the car told how many were found`() {
        shelves.searched = CarAnswer.Nodes(listOf(road, river))

        val asked = browser.search(link, "road", null).now()

        assertThat(asked.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(link.found).containsExactly("road" to 2)
        assertThat(shelves.searches).containsExactly("road")
    }

    @Test
    fun `the result of a search is the one kept, page by page, without searching again`() {
        shelves.searched = CarAnswer.Nodes(listOf(road, river))
        browser.search(link, "road", null).now()

        assertThat(browser.searchResult(link, "road", 0, 1).now().ids()).containsExactly("book/1")
        assertThat(browser.searchResult(link, "road", 1, 1).now().ids()).containsExactly("book/2")
        assertThat(browser.searchResult(link, "road", 2, 1).now().ids()).isEmpty()
        assertThat(shelves.searches).containsExactly("road")
    }

    @Test
    fun `a result asked for words that were not searched, or by another car, is searched then`() {
        shelves.searched = CarAnswer.Nodes(listOf(road))
        browser.search(link, "road", null).now()

        assertThat(browser.searchResult(link, "river", 0, 100).now().ids()).containsExactly("book/1")
        assertThat(browser.searchResult(FakeCarLink("another car"), "road", 0, 100).now().ids())
            .containsExactly("book/1")
        assertThat(shelves.searches).containsExactly("road", "river", "road").inOrder()
    }

    @Test
    fun `a search that cannot be run tells the car there is something to read, and its result says why`() {
        shelves.searched = CarAnswer.Notice(CarNotice.Unreachable)

        assertThat(browser.search(link, "road", null).now().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(link.found).containsExactly("road" to 1)
        assertThat(link.said).isEmpty()

        val result = browser.searchResult(link, "road", 0, 100).now()
        assertThat(checkNotNull(result.value).single().mediaMetadata.title.toString())
            .isEqualTo("The server cannot be reached.")
        assertThat(link.said).isEmpty()
    }

    @Test
    fun `a car that left has its search forgotten`() {
        shelves.searched = CarAnswer.Nodes(listOf(road))
        browser.search(link, "road", null).now()

        browser.forget(link.controller)
        browser.searchResult(link, "road", 0, 100).now()

        assertThat(shelves.searches).containsExactly("road", "road")
    }

    // --- the profile closed

    @Test
    fun `once the profile closed, every question is refused and the library is asked nothing`() {
        closed = true

        val codes = listOf(
            browser.root().now().resultCode,
            browser.children(link, "root", 0, 100).now().resultCode,
            browser.item("tab/home").now().resultCode,
            browser.search(link, "road", null).now().resultCode,
            browser.searchResult(link, "road", 0, 100).now().resultCode,
        )

        assertThat(codes.toSet()).containsExactly(SessionError.ERROR_INVALID_STATE)
        assertThat(shelves.asked).isEmpty()
        assertThat(shelves.searches).isEmpty()
        assertThat(link.found).isEmpty()
    }

    @Test
    fun `a question the service ended under is refused, not left without an answer`() {
        scope.cancel()

        assertThat(browser.children(link, "tab/home", 0, 100).now().resultCode)
            .isEqualTo(SessionError.ERROR_INVALID_STATE)
        assertThat(browser.search(link, "road", null).now().resultCode).isEqualTo(SessionError.ERROR_INVALID_STATE)
    }
}
