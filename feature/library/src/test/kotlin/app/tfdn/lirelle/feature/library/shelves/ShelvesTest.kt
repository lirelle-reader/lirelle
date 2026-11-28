package app.tfdn.lirelle.feature.library.shelves

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ShelvesTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeLibraryRepository()
    private val smart = Shelf(ShelfKind.SmartList, ShelfId("3"), "To read soon", 14)
    private val uncounted = Shelf(ShelfKind.SmartList, ShelfId("4"), "Broken rule", null)
    private val collection = Shelf(ShelfKind.Collection, ShelfId("7"), "Favourites", 1)
    private val playlist = Shelf(ShelfKind.Playlist, ShelfId("p:9"), "Road trip", 3)
    private val opened = mutableListOf<Shelf>()

    private fun text(id: Int) = context.getString(id)

    private fun showTab(capabilities: SourceCapabilities = SourceCapabilities.Everything) {
        val viewModel = ShelvesViewModel(repository, capabilities)
        compose.setContent { LirelleTheme { ShelvesTab(onOpenShelf = { opened += it }, viewModel = viewModel) } }
    }

    @Test
    fun `a source with collections only shows only its collections`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart, uncounted), listOf(collection)))
        showTab(KomgaLikeCapabilities)

        compose.onNodeWithText(text(R.string.shelves_collections)).assertIsDisplayed()
        compose.onNodeWithText("Favourites").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shelves_smart_lists)).assertDoesNotExist()
        compose.onNodeWithText("To read soon").assertDoesNotExist()
        compose.onNodeWithText("Broken rule").assertDoesNotExist()
    }

    @Test
    fun `a source with collections only and none made says there is no shelf`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), emptyList()))
        showTab(KomgaLikeCapabilities)

        compose.onNodeWithText(text(R.string.shelves_empty)).assertIsDisplayed()
    }

    @Test
    fun `smart lists come first, then collections, each with its number of books`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart, uncounted), listOf(collection)))
        showTab()

        compose.onNodeWithText(text(R.string.shelves_smart_lists)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shelves_collections)).assertIsDisplayed()
        compose.onNodeWithText("To read soon").assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.books_count, 14, 14)).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.books_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("Broken rule").assertIsDisplayed()
    }

    @Test
    fun `a group without shelves has no heading`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), emptyList()))
        showTab()

        compose.onNodeWithText(text(R.string.shelves_smart_lists)).assertIsDisplayed()
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.shelves_collections)))
            .fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `tapping a shelf opens it`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), listOf(collection)))
        showTab()

        compose.onNodeWithTag("shelf-Collection-7").performClick()

        assertThat(opened).containsExactly(collection)
    }

    @Test
    fun `no shelves at all says where to create them`() {
        showTab()

        compose.onNodeWithText(text(R.string.shelves_empty)).assertIsDisplayed()
    }

    @Test
    fun `a failure shows the error and retries`() {
        repository.shelvesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        showTab()
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), emptyList()))

        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.state_retry)).performClick()

        compose.onNodeWithText("To read soon").assertIsDisplayed()
    }

    @Test
    fun `sources follow the kind of shelf`() {
        assertThat(smart.source()).isEqualTo(BookListSource.SmartList(ShelfId("3")))
        assertThat(collection.source()).isEqualTo(BookListSource.Collection(ShelfId("7")))
    }

    @Test
    fun `playlists are a section of their own, after the collections`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), listOf(collection), listOf(playlist)))
        showTab()

        val collections = compose.onNodeWithText(text(R.string.shelves_collections)).getUnclippedBoundsInRoot()
        val playlists = compose.onNodeWithText(text(R.string.shelves_playlists)).assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        compose.onNodeWithText("Road trip").assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.books_count, 3, 3)).assertIsDisplayed()
        assertThat(playlists.top).isGreaterThan(collections.top)
        assertThat(compose.onNodeWithText("Road trip").getUnclippedBoundsInRoot().top)
            .isGreaterThan(compose.onNodeWithText("Favourites").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `no playlists, no Playlists section`() {
        repository.shelvesResult = ApiResult.Success(Shelves(listOf(smart), listOf(collection)))
        showTab()

        compose.onNodeWithText(text(R.string.shelves_playlists)).assertDoesNotExist()
    }

    @Test
    fun `playlists alone are shown, not the empty message`() {
        repository.shelvesResult = ApiResult.Success(Shelves(emptyList(), emptyList(), listOf(playlist)))
        showTab()

        compose.onNodeWithText(text(R.string.shelves_playlists)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shelves_empty)).assertDoesNotExist()
    }

    @Test
    fun `a source without playlists shows none, even if listed`() {
        repository.shelvesResult = ApiResult.Success(Shelves(emptyList(), listOf(collection), listOf(playlist)))
        showTab(KomgaLikeCapabilities)

        compose.onNodeWithText("Favourites").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shelves_playlists)).assertDoesNotExist()
        compose.onNodeWithText("Road trip").assertDoesNotExist()
    }

    @Test
    fun `tapping a playlist opens it`() {
        repository.shelvesResult = ApiResult.Success(Shelves(emptyList(), listOf(collection), listOf(playlist)))
        showTab()

        compose.onNodeWithTag("shelf-Playlist-p:9").performClick()

        assertThat(opened).containsExactly(playlist)
    }

    @Test
    fun `a playlist opens its books as a collection does, told apart by its id`() {
        assertThat(playlist.source()).isEqualTo(BookListSource.Collection(ShelfId("p:9")))
    }

    @Test
    fun `the shelf screen shows its name and its books`() {
        repository.books = listOf(sampleBook("1"))
        val source = BookListSource.Collection(ShelfId("7"))
        val viewModel = BookListViewModel(
            BookListScope.shelf(source),
            repository,
            InMemoryLibraryPreferences(),
            SourceCapabilities.Everything,
            FakeLibraryChoice(),
        )
        val openedBooks = mutableListOf<BookId>()
        compose.setContent {
            LirelleTheme {
                ShelfScreen(source, name = "Favourites", onBack = {}, onOpenBook = { openedBooks += it }, viewModel)
            }
        }

        compose.onNodeWithText("Favourites").assertIsDisplayed()
        compose.onNodeWithTag("book-1").performClick()

        assertThat(openedBooks).containsExactly(BookId("1"))
        assertThat(repository.bookRequests.last().first).isEqualTo(source)
    }
}
