package app.tfdn.lirelle.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chapters, speed and sleep timer sheets, and the end panel: what they show and what they ask for. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerSheetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()

    private var shown by mutableStateOf(PlayerSamples.playing)

    private fun show(state: PlayerUiState) {
        shown = state
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(shown, actions, onMinimise = {}, onOpenBook = {}, onOpenSettings = {})
            }
        }
    }

    private fun open(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun stateDescription(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private val shakeSwitch
        get() = compose.onNode(
            hasText("Shake to extend") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        )

    @Test
    fun `the chapters, heard ones and the current one with its time left, with how many and how long`() {
        show(PlayerSamples.playing)
        open(ChaptersButtonTag)

        compose.onNodeWithText("3 chapters · 3 min").assertIsDisplayed()
        compose.onNode(hasText("Opening") and hasText("1:00")).assert(stateDescription("heard"))
        compose.onNode(hasText("Middle") and hasText("−0:30")).assert(stateDescription("playing"))
    }

    @Test
    fun `at 2x the current chapter's time left is the listening time, the others keep their length`() {
        show(PlayerSamples.playing.copy(speed = 2f))
        open(ChaptersButtonTag)

        compose.onNode(hasText("Middle") and hasText("−0:15")).assert(stateDescription("playing"))
        compose.onNode(hasText("Opening") and hasText("1:00")).assert(stateDescription("heard"))
        compose.onNode(hasText("Closing") and hasText("1:00")).assertIsDisplayed()
    }

    @Test
    fun `a chapter chosen is gone to, and the sheet closes`() {
        show(PlayerSamples.playing)
        open(ChaptersButtonTag)

        compose.onNode(hasText("Closing")).performClick()

        assertThat(actions.asked).containsExactly("goToChapter 2")
        compose.onNodeWithTag(ChaptersSheetTag).assertDoesNotExist()
    }

    @Test
    fun `the speed, a step down and up, and the shortcuts`() {
        show(PlayerSamples.playing.copy(speed = 1.25f))
        open(SpeedButtonTag)

        compose.onNodeWithText("For this book").assertIsDisplayed()
        compose.onNodeWithContentDescription("Slower").performClick()
        compose.onNodeWithContentDescription("Faster").performClick()
        compose.onNode(hasText("1.25×") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertIsSelected()
        compose.onNodeWithText("2×").performClick()

        assertThat(actions.asked).containsExactly("setSpeed 1.2", "setSpeed 1.25", "setSpeed 2.0").inOrder()
    }

    @Test
    fun `two quick taps make two steps, before the session has the first`() {
        show(PlayerSamples.playing.copy(speed = 1.25f))
        open(SpeedButtonTag)

        compose.onNodeWithContentDescription("Faster").performClick()
        compose.onNodeWithContentDescription("Faster").performClick()

        assertThat(actions.asked).containsExactly("setSpeed 1.3", "setSpeed 1.35").inOrder()
    }

    @Test
    fun `the speed the session says replaces the one asked, whatever it is`() {
        show(PlayerSamples.playing.copy(speed = 1.25f))
        open(SpeedButtonTag)
        compose.onNodeWithContentDescription("Faster").performClick()
        compose.onNodeWithText("1.3×").assertIsDisplayed()

        shown = PlayerSamples.playing.copy(speed = 1.75f)

        // In the sheet and on the tile behind it.
        assertThat(compose.onAllNodesWithText("1.75×").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("1.3×").assertDoesNotExist()
    }

    @Test
    fun `a speed asked that the session never says is dropped after a while`() {
        show(PlayerSamples.playing.copy(speed = 1.25f))
        open(SpeedButtonTag)
        compose.onNodeWithContentDescription("Faster").performClick()
        compose.onNodeWithText("1.3×").assertExists()

        compose.mainClock.advanceTimeBy(2_500)

        compose.onNodeWithText("1.3×").assertDoesNotExist()
    }

    @Test
    fun `a screen reader hears the speed on the slider`() {
        show(PlayerSamples.playing.copy(speed = 1.25f))
        open(SpeedButtonTag)

        compose.onNodeWithTag(SpeedSliderTag).assert(stateDescription("1.25×"))
    }

    @Test
    fun `the speed cannot go past its ends`() {
        show(PlayerSamples.playing.copy(speed = 3f))
        open(SpeedButtonTag)

        compose.onNodeWithContentDescription("Faster").assertIsNotEnabled()
    }

    @Test
    fun `the slider sets the speed when the finger is lifted`() {
        show(PlayerSamples.playing)
        open(SpeedButtonTag)
        val slider = compose.onNodeWithTag(SpeedSliderTag)

        slider.performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(width / 10f, 0f)) }
        }
        assertThat(actions.asked).isEmpty()
        slider.performTouchInput { up() }

        assertThat(actions.asked).hasSize(1)
        assertThat(actions.asked.single()).startsWith("setSpeed ")
    }

    @Test
    fun `the time left at the speed`() {
        val hours = 60 * 60_000L
        show(PlayerSamples.playing.copy(speed = 1.5f, bookTimeMs = hours, totalMs = 10 * hours))
        open(SpeedButtonTag)

        compose.onNodeWithText("Time left at this speed: 6 h 00").assertIsDisplayed()
    }

    @Test
    fun `the sleep timer's choices, the end of the chapter first`() {
        show(PlayerSamples.playing)
        open(SleepButtonTag)

        val labels = listOf("End of chapter") + listOf(5, 10, 15, 30, 45, 60).map { "$it minutes" }
        val tops = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertThat(tops).isInStrictOrder()
        compose.onNodeWithText("Stop the timer").assertDoesNotExist()
    }

    @Test
    fun `the last choice comes first`() {
        assertThat(sleepChoices(SleepChoice.Minutes(30))).containsExactly(
            SleepChoice.Minutes(30),
            SleepChoice.EndOfChapter,
            SleepChoice.Minutes(5),
            SleepChoice.Minutes(10),
            SleepChoice.Minutes(15),
            SleepChoice.Minutes(45),
            SleepChoice.Minutes(60),
        ).inOrder()
        assertThat(sleepChoices(null).first()).isEqualTo(SleepChoice.EndOfChapter)
    }

    @Test
    fun `a running timer is checked, says when it stops, and can be stopped`() {
        val sleep = SleepState(SleepChoice.Minutes(30), remainingMs = 23 * 60_000L, fading = false)
        val settings = PlayerSettings(lastSleepChoice = SleepChoice.Minutes(30))
        show(PlayerSamples.playing.copy(sleep = sleep, settings = settings))
        open(SleepButtonTag)

        compose.onNodeWithText("Stops in 23 min").assertIsDisplayed()
        compose.onNodeWithText("30 minutes").assertIsSelected()
        compose.onNodeWithText("Stop the timer").performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("cancelSleep")
        compose.onNodeWithTag(SleepSheetTag).assertDoesNotExist()
    }

    @Test
    fun `a choice starts the timer and closes the sheet`() {
        show(PlayerSamples.playing)
        open(SleepButtonTag)

        compose.onNodeWithText("End of chapter").performClick()

        assertThat(actions.asked).containsExactly("startSleep EndOfChapter")
        compose.onNodeWithTag(SleepSheetTag).assertDoesNotExist()
    }

    @Test
    fun `the end of the chapter says so`() {
        show(PlayerSamples.playing.copy(sleep = SleepState(SleepChoice.EndOfChapter, null, fading = false)))
        open(SleepButtonTag)

        compose.onNodeWithText("Stops at the end of the chapter").assertIsDisplayed()
    }

    @Test
    fun `shaking to extend is turned off and on`() {
        show(PlayerSamples.playing)
        open(SleepButtonTag)

        compose.onNodeWithText("Shake to extend").performScrollTo()
        shakeSwitch.assertIsOn().performClick()

        assertThat(actions.asked).containsExactly("setShakeToExtend false")
    }

    @Test
    fun `shaking to extend shows off when it is`() {
        show(PlayerSamples.playing.copy(settings = PlayerSettings(shakeToExtend = false)))
        open(SleepButtonTag)

        shakeSwitch.assertIsOff()
    }

    @Test
    fun `the end panel, its listening time, stars, next volume and close`() {
        val next = NextVolume(BookId("43"), "The Long Road Back", "2")
        show(finishedBook(next = next, listenedSeconds = (14 * 60 + 38) * 60L))

        compose.onNodeWithText("Book finished").assertIsDisplayed()
        compose.onNodeWithText("14 h 38 of listening").assertIsDisplayed()
        compose.onNodeWithText("Your rating (optional)").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 stars").performClick()
        compose.onNodeWithText("The Long Road Back").assertIsDisplayed()
        compose.onNodeWithText("Listen to the next one").performClick()
        compose.onNodeWithText("Close").performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("setRating 4", "listenToNext", "closeEndPanel").inOrder()
    }

    @Test
    fun `the end panel without a listening time leaves its line out`() {
        show(finishedBook())

        compose.onNodeWithText("of listening", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Listen to the next one").assertDoesNotExist()
    }

    @Test
    fun `the end panel is dismissed by a tap above it, a swipe down and back`() {
        show(finishedBook())

        compose.onNodeWithTag(PanelSheetTag).performTouchInput { swipeDown(startY = top + 10f, endY = bottom) }
        compose.onRoot().performTouchInput { click(Offset(centerX, 5f)) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertThat(actions.asked).containsExactly("dismissEndPanel", "dismissEndPanel", "dismissEndPanel")
    }

    @Test
    fun `a rating that could not be sent says so and asks again what was asked`() {
        show(finishedBook(failed = true, rating = 3))

        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithText("The rating could not be sent.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertThat(actions.asked).containsExactly("closeEndPanel", "closeEndPanel").inOrder()
    }

    @Test
    fun `while the rating is on its way the panel waits`() {
        show(finishedBook(sending = true, rating = 5))

        compose.onNodeWithText("Close").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Sending the rating").assertIsDisplayed()
    }

    private fun finishedBook(
        next: NextVolume? = null,
        listenedSeconds: Long? = null,
        failed: Boolean = false,
        sending: Boolean = false,
        rating: Int? = null,
    ) = PlayerSamples.playing.copy(
        playing = false,
        finished = FinishedState(rating, next, sending = sending, failed = failed, panelSeen = false),
        listenedSeconds = listenedSeconds,
    )
}
