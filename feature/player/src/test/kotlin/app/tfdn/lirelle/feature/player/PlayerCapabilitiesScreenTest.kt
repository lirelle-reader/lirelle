package app.tfdn.lirelle.feature.player

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkButtonTag
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A source with audiobooks but neither audio bookmarks nor ratings. */
internal val AudiobooksAlone = KomgaLikeCapabilities.copy(audiobooks = true)

/**
 * The player of a source with audiobooks but no bookmarks nor ratings, on a phone as on a tablet (spec 2c): no
 * bookmark button nor tab, no stars at the end; the mini-player offers no rating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerCapabilitiesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()
    private val asked = mutableListOf<String>()

    private val playing = PlayerSamples.playing.copy(capabilities = AudiobooksAlone)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: PlayerUiState) {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(state = state, actions = actions, onMinimise = {}, onOpenBook = {}, onOpenSettings = {})
            }
        }
    }

    private fun finished(panelSeen: Boolean) = playing.copy(
        playing = false,
        finished = FinishedState(rating = null, nextVolume = null, sending = false, failed = false, panelSeen),
    )

    private fun assertNoBookmarksTab() {
        compose.onNodeWithText("Closing").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.player_bookmarks_tab, 0)).assertCountEquals(0)
    }

    @Test
    fun `on a phone there is no bookmark button, and the chapters sheet has no bookmarks tab`() {
        show(playing)

        compose.onNodeWithTag(BookmarkButtonTag).assertDoesNotExist()
        compose.onNodeWithTag(ChaptersButtonTag).performClick()

        assertNoBookmarksTab()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet there is no bookmark button, and the chapters column has no bookmarks tab`() {
        show(playing)

        compose.onNodeWithTag(BookmarkButtonTag).assertDoesNotExist()
        compose.onNodeWithTag(ChaptersColumnTag).assertIsDisplayed()

        assertNoBookmarksTab()
    }

    @Test
    fun `on a phone the end panel has no stars without ratings`() {
        show(finished(panelSeen = false))

        compose.onNodeWithText(text(R.string.player_end_finished)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.player_end_rating)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("1 star").assertCountEquals(0)
        compose.onNodeWithText(text(R.string.player_end_close)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the end panel has no stars without ratings`() {
        show(finished(panelSeen = false))

        compose.onNodeWithText(text(R.string.player_end_finished)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.player_end_rating)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("1 star").assertCountEquals(0)
    }

    @Test
    fun `a finished book whose panel was dismissed offers no rating, and still opens its panel`() {
        show(finished(panelSeen = true))

        compose.onAllNodesWithContentDescription(text(R.string.player_rate)).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.player_end_finished)).performClick()

        assertThat(actions.asked).containsExactly("openEndPanel")
    }

    @Test
    fun `the player opened without audiobooks goes back`() {
        val viewModel = PlayerHarness(TestScope()).viewModel(capabilities = KomgaLikeCapabilities)
        var backs = 0
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerScreen(viewModel, onMinimise = { backs++ }, onOpenBook = {}, onOpenSettings = {})
            }
        }
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
        compose.onAllNodesWithText(text(R.string.player_now_playing)).assertCountEquals(0)
    }

    @Test
    fun `the mini-player of a finished book offers no rating, and its bar opens the end panel`() {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                MiniPlayerContent(
                    state = finished(panelSeen = true),
                    onOpenPlayer = { asked += "open" },
                    onPlayPause = { asked += "playPause" },
                    onBack = { asked += "back" },
                    onRate = { asked += "rate" },
                )
            }
        }

        compose.onAllNodesWithText(text(R.string.player_mini_rate)).assertCountEquals(0)
        val bar = compose.onNodeWithTag(MiniPlayerTag)
        assertThat(bar.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            .isEqualTo(text(R.string.player_mini_open))
        bar.performClick()

        assertThat(asked).containsExactly("rate")
    }
}
