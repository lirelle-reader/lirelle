package app.tfdn.lirelle.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the mini-player shows of the book loaded, what its buttons ask for, and when it is not there. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MiniPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String>()
    private var state by mutableStateOf(PlayerSamples.playing)
    private var narration by mutableStateOf<NarrationUi?>(null)

    private fun show(shown: PlayerUiState = PlayerSamples.playing, narrated: NarrationUi? = null) {
        state = shown
        narration = narrated
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                MiniPlayerContent(
                    state = state,
                    onOpenPlayer = { asked += "open" },
                    onPlayPause = { asked += "playPause" },
                    onBack = { asked += "back" },
                    onRate = { asked += "rate" },
                    narration = narration,
                    onOpenReader = { asked += "reader $it" },
                )
            }
        }
    }

    private fun byLabel(label: String) = compose.onNodeWithContentDescription(label)

    @Test
    fun `the book's title, its chapter and the time left in the chapter`() {
        show()

        compose.onNodeWithText("The Long Road").assertIsDisplayed()
        // 10 s into a chapter of 40 s.
        compose.onNodeWithText("Middle · −0:30").assertIsDisplayed()
    }

    @Test
    fun `at 2x the chapter's time left is the listening time`() {
        show(PlayerSamples.playing.copy(speed = 2f))

        compose.onNodeWithText("Middle · −0:15").assertIsDisplayed()
    }

    @Test
    fun `a running sleep timer takes the place of the time left`() {
        show(PlayerSamples.playing.copy(sleep = SleepState(SleepChoice.Minutes(15), 12 * 60_000L, fading = false)))

        compose.onNodeWithText("Middle · Stops in 12 min").assertIsDisplayed()
    }

    @Test
    fun `a sleep timer at the end of the chapter says so`() {
        show(PlayerSamples.playing.copy(sleep = SleepState(SleepChoice.EndOfChapter, null, fading = false)))

        compose.onNodeWithText("Middle · Stops at the end of the chapter").assertIsDisplayed()
    }

    @Test
    fun `a chapter of unknown length shows its title alone`() {
        show(PlayerSamples.playing.copy(chapterDurationMs = null))

        compose.onNodeWithText("Middle").assertIsDisplayed()
    }

    @Test
    fun `back goes back by the configured interval, and says by how much`() {
        show(PlayerSamples.playing.copy(settings = PlayerSettings(backSeconds = 30)))

        byLabel("Back 30 seconds").performClick()

        assertThat(asked).containsExactly("back")
    }

    @Test
    fun `the play button pauses a book that plays, and is named for it`() {
        show()

        byLabel("Pause").performClick()

        assertThat(asked).containsExactly("playPause")
    }

    @Test
    fun `a paused book shows play`() {
        show(PlayerSamples.playing.copy(playing = false))

        byLabel("Play").performClick()

        assertThat(asked).containsExactly("playPause")
    }

    @Test
    fun `a tap on the mini-player opens the player, which a screen reader announces`() {
        show()

        val node = compose.onNodeWithTag(MiniPlayerTag)
        assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
            .isEqualTo("Open the player")
        node.performClick()

        assertThat(asked).containsExactly("open")
    }

    @Test
    fun `the progress line shows how far into the book playback is`() {
        show()

        val card = compose.onNodeWithTag(MiniPlayerTag).fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag(MiniPlayerProgressTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertThat(line.width / card.width).isWithin(Tolerance).of(0.4375f)
        assertThat(line.bottom).isEqualTo(card.bottom)
    }

    @Test
    fun `the progress line tells a screen reader how far playback is`() {
        show()

        compose.onNodeWithTag(MiniPlayerProgressTag, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.4375f, 0f..1f),
                ),
            )
    }

    @Test
    fun `nothing is shown while no book is loaded`() {
        show(PlayerUiState())

        compose.onNodeWithTag(MiniPlayerTag).assertDoesNotExist()
    }

    @Test
    fun `nothing is shown while a first book is still being read`() {
        show(PlayerUiState(loading = true))

        compose.onNodeWithTag(MiniPlayerTag).assertDoesNotExist()
    }

    @Test
    fun `it goes away when the book is closed`() {
        show()
        compose.onNodeWithTag(MiniPlayerTag).assertIsDisplayed()

        state = PlayerUiState()

        compose.onNodeWithTag(MiniPlayerTag).assertDoesNotExist()
    }

    @Test
    fun `a finished book says so and offers to rate it, which opens the end panel in the player`() {
        show(PlayerSamples.playing.copy(playing = false, finished = finished))

        compose.onNodeWithText("Finished").assertIsDisplayed()
        byLabel("Pause").assertDoesNotExist()
        byLabel("Play").assertDoesNotExist()
        byLabel("Back 15 seconds").assertDoesNotExist()
        compose.onNodeWithText("Rate").performClick()

        assertThat(asked).containsExactly("rate")
    }

    @Test
    fun `a tap on the bar of a finished book opens the end panel too, which a screen reader announces`() {
        show(PlayerSamples.playing.copy(playing = false, finished = finished))

        val node = compose.onNodeWithTag(MiniPlayerTag)
        assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
            .isEqualTo("Rate the book")
        node.performClick()

        assertThat(asked).containsExactly("rate")
    }

    @Test
    fun `the title is said once, the cover in the bar says nothing`() {
        show()

        val title = PlayerSamples.playing.title
        compose.onAllNodes(hasText(title) or hasContentDescription(title), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun `a narration shows the wave and its section, and plays and pauses as a book does`() {
        show(PlayerSamples.narrating, PlayerSamples.narration)

        compose.onNodeWithText("The Long Road").assertIsDisplayed()
        compose.onNodeWithText("Narration · Section 4").assertIsDisplayed()
        compose.onNodeWithTag(MiniPlayerNarrationTag, useUnmergedTree = true).assertIsDisplayed()
        byLabel("Pause").performClick()

        assertThat(asked).containsExactly("playPause")
    }

    @Test
    fun `an audiobook shows no wave`() {
        show()

        compose.onNodeWithTag(MiniPlayerNarrationTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tap on a narration opens the reader on its book, which a screen reader announces`() {
        show(PlayerSamples.narrating, PlayerSamples.narration)

        val node = compose.onNodeWithTag(MiniPlayerTag)
        assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
            .isEqualTo("Open the reader")
        node.performClick()

        assertThat(asked).containsExactly("reader 42")
    }

    @Test
    fun `a narration shown by the player's state before its own still opens the reader, never the player`() {
        show(PlayerSamples.narrating, narrated = null)

        compose.onNodeWithTag(MiniPlayerTag).performClick()

        assertThat(asked).containsExactly("reader 42")
    }

    @Test
    fun `a finished narration says so, offers no rating, and opens the reader`() {
        show(
            PlayerSamples.narrating.copy(playing = false),
            PlayerSamples.narration.copy(playing = false, finished = true),
        )

        compose.onNodeWithText("Narration finished").assertIsDisplayed()
        compose.onNodeWithText("Rate").assertDoesNotExist()
        byLabel("Play").assertDoesNotExist()
        byLabel("Back 15 seconds").assertDoesNotExist()
        compose.onNodeWithTag(MiniPlayerTag).performClick()

        assertThat(asked).containsExactly("reader 42")
    }

    private val finished =
        FinishedState(rating = null, nextVolume = null, sending = false, failed = false, panelSeen = true)

    private companion object {
        const val Tolerance = 0.01f
    }
}
