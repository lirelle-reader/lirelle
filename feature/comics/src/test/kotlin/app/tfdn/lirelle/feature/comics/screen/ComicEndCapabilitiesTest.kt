package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The end of an album from a source without ratings, on a phone as on a tablet: no stars (spec 2c). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Phone)
class ComicEndCapabilitiesTest {

    @get:Rule
    val compose = createComposeRule()

    private val next = NextVolume(bookId = BookId("15"), title = "Arctic-Nation", seriesIndex = "2")
    private val screen = ScreenUnderTest(openAlbum(spreadIndex = 47, barsShown = false))

    private fun showWithoutRatings() {
        screen.state = screen.state.copy(endPanel = ComicEndPanel(nextVolume = next, ratings = false))
        compose.setContent { screen.Content() }
        compose.waitForIdle()
    }

    private fun assertNoRating() {
        compose.onNodeWithText("You finished this album").assertExists()
        compose.onAllNodesWithText("Your rating (optional)").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("1 star").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("5 stars").assertCountEquals(0)
        compose.onNodeWithText("Arctic-Nation").assertExists()
        compose.onNodeWithContentDescription("Read the next one").performClick()
        compose.onNodeWithContentDescription("Close the album").assertExists()
        assertThat(screen.actions.calls).contains("readNext")
    }

    @Test
    fun `on a phone the end of an album has no stars without ratings`() {
        showWithoutRatings()

        assertNoRating()
    }

    @Test
    @Config(qualifiers = Wide)
    fun `on a tablet the end of an album has no stars without ratings`() {
        showWithoutRatings()

        assertNoRating()
    }
}
