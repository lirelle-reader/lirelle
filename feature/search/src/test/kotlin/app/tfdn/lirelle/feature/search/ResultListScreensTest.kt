package app.tfdn.lirelle.feature.search

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleSeries
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as CoreR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ResultListScreensTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakeLibraryRepository()
    /** The series and authors opened, by their ids. */
    private val opened = mutableListOf<Any>()
    private var backs = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun books(count: Int) = context.resources.getQuantityString(R.plurals.search_book_count, count, count)

    private fun showSeries(capabilities: SourceCapabilities = SourceCapabilities.Everything) {
        // Built outside the composition: a view model is not something a composable constructs.
        val viewModel = SeriesResultsViewModel("mars", fake, capabilities)
        compose.setContent {
            LirelleTheme {
                SeriesResultsScreen(
                    text = "mars",
                    onBack = { backs++ },
                    onOpenSeries = { opened += it },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun showAuthors() {
        val viewModel = AuthorResultsViewModel("writer", fake, SourceCapabilities.Everything)
        compose.setContent {
            LirelleTheme {
                AuthorResultsScreen(
                    text = "writer",
                    onBack = { backs++ },
                    onOpenAuthor = { opened += it },
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun `the series list names the text and opens a series`() {
        fake.seriesResult = ApiResult.Success(listOf(sampleSeries("40", "A Saga"), sampleSeries("41", "Another Saga")))
        showSeries()

        compose.onNodeWithText(text(R.string.search_series_title, "mars")).assertIsDisplayed()
        compose.onNodeWithTag("result-series-41").performClick()
        compose.onNodeWithContentDescription(text(CoreR.string.navigate_back)).performClick()

        assertThat(opened).containsExactly(SeriesId("41"))
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a series found shows its number of books`() {
        fake.seriesResult = ApiResult.Success(listOf(sampleSeries("40", "A Saga", books = 7)))
        showSeries()

        compose.onNodeWithText(books(7)).assertIsDisplayed()
    }

    @Test
    fun `without series counts a series found shows no number of books`() {
        fake.seriesResult = ApiResult.Success(listOf(sampleSeries("40", "A Saga", books = 7)))
        showSeries(SourceCapabilities.Everything.copy(seriesCounts = false))

        compose.onNodeWithText("A Saga").assertIsDisplayed()
        compose.onNodeWithText(books(7)).assertDoesNotExist()
    }

    @Test
    fun `a series list with nothing says so`() {
        showSeries()
        compose.onNodeWithText(text(R.string.search_nothing, "mars")).assertIsDisplayed()
    }

    @Test
    fun `a failed series list offers to retry`() {
        fake.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        showSeries()

        compose.onNodeWithText(text(CoreR.string.state_retry)).performClick()

        assertThat(fake.calls).containsExactly("series mars", "series mars")
    }

    @Test
    fun `the author list names the text and opens an author`() {
        fake.authorsList = listOf(Author(AuthorId("50"), "Ursula Writer", bookCount = 4, portraitUrl = null))
        showAuthors()

        compose.onNodeWithText(text(R.string.search_authors_title, "writer")).assertIsDisplayed()
        compose.onNodeWithTag("result-author-50").performClick()

        assertThat(opened).containsExactly(AuthorId("50"))
    }

    @Test
    fun `an author list with nothing says so`() {
        showAuthors()

        compose.onNodeWithText(text(R.string.search_nothing, "writer")).assertIsDisplayed()
    }
}
