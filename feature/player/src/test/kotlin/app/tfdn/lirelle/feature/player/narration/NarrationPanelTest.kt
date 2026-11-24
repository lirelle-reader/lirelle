package app.tfdn.lirelle.feature.player.narration

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.SheetGripTag
import app.tfdn.lirelle.feature.player.ChapterBarTag
import app.tfdn.lirelle.feature.player.ChaptersButtonTag
import app.tfdn.lirelle.feature.player.ChaptersSheetTag
import app.tfdn.lirelle.feature.player.PlayerSamples
import app.tfdn.lirelle.feature.player.SleepButtonTag
import app.tfdn.lirelle.feature.player.SleepSheetTag
import app.tfdn.lirelle.feature.player.SpeedButtonTag
import app.tfdn.lirelle.feature.player.SpeedSheetTag
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The narration's panel: what it shows, its controls, and the sheets its tiles open. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NarrationPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingNarrationActions()
    private var dismissals = 0

    private fun show(state: NarrationUi = PlayerSamples.narration) {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                NarrationPanel(state, LightNarrationColors, actions, onDismiss = { dismissals++ })
            }
        }
    }

    private fun open(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun `the panel shows the section, where it is in the narration, and its times`() {
        show()

        compose.onNodeWithText("Section 4").assertIsDisplayed()
        compose.onNodeWithText("Section 2 of 2 · 76% of the book").assertIsDisplayed()
        compose.onNodeWithText("0:04").assertIsDisplayed()
        compose.onNodeWithText("−0:06").assertIsDisplayed()
        compose.onNodeWithContentDescription("Position in the section").assertIsDisplayed()
    }

    @Test
    fun `at 2x the section's time left is the listening time`() {
        show(PlayerSamples.narration.copy(speed = 2f))

        compose.onNodeWithText("0:04").assertIsDisplayed()
        compose.onNodeWithText("−0:03").assertIsDisplayed()
    }

    @Test
    fun `after a section chosen, the panel offers to go back, which asks for it`() {
        show(PlayerSamples.narration.copy(goBackShown = true))

        compose.onNodeWithText("Go back").performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("goBack")
    }

    @Test
    fun `nothing to go back to, no "Go back"`() {
        show()

        compose.onNodeWithText("Go back").assertDoesNotExist()
    }

    @Test
    fun `each control of the panel asks for its own`() {
        show()

        compose.onNodeWithContentDescription("Back 15 seconds").performClick()
        compose.onNodeWithContentDescription("Previous sentence").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next sentence").performClick()
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()

        assertThat(actions.asked)
            .containsExactly("back", "previousSentence", "playPause", "nextSentence", "forward").inOrder()
    }

    @Test
    fun `the section's bar seeks in the section when the finger is lifted`() {
        show()
        val bar = compose.onNodeWithTag(ChapterBarTag)

        bar.performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(width / 10f, 0f)) }
        }
        assertThat(actions.asked).isEmpty()
        bar.performTouchInput { up() }

        assertThat(actions.asked).containsExactly("seekInSection")
    }

    @Test
    fun `the speed tile opens the speed sheet`() {
        show(PlayerSamples.narration.copy(speed = 1.25f))

        compose.onNode(hasText("1.25×") and hasText("Speed")).assertIsDisplayed()
        open(SpeedButtonTag)
        compose.onNodeWithTag(SpeedSheetTag).assertIsDisplayed()
        compose.onNodeWithText("2×").performClick()

        assertThat(actions.asked).containsExactly("setSpeed 2.0")
    }

    @Test
    fun `the sleep tile shows the time left and opens the sleep timer, which can be stopped`() {
        show(PlayerSamples.narration.copy(sleep = SleepState(SleepChoice.Minutes(15), 600_000, fading = false)))

        compose.onNode(hasText("10 min") and hasText("Sleep")).assertIsDisplayed()
        open(SleepButtonTag)
        compose.onNodeWithTag(SleepSheetTag).assertIsDisplayed()
        compose.onNodeWithText("Stop the timer").performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("cancelSleep")
    }

    @Test
    fun `a narration's timer stops at the end of a section`() {
        show(PlayerSamples.narration.copy(sleep = SleepState(SleepChoice.EndOfChapter, null, fading = false)))

        open(SleepButtonTag)

        compose.onNodeWithText("Stops at the end of the section").assertIsDisplayed()
        compose.onNodeWithText("End of section").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, a narration's timer stops at the end of a section`() {
        show(PlayerSamples.narration.copy(sleep = SleepState(SleepChoice.EndOfChapter, null, fading = false)))

        open(SleepButtonTag)

        compose.onNodeWithText("Arrêt en fin de section").assertIsDisplayed()
        compose.onNodeWithText("Fin de la section").assertIsDisplayed()
    }

    @Test
    fun `the sections tile opens the sections, and one chosen is gone to`() {
        show()

        compose.onNode(hasText("Sections")).assertIsDisplayed()
        open(ChaptersButtonTag)
        compose.onNodeWithTag(ChaptersSheetTag).assertIsDisplayed()
        compose.onNodeWithText("2 sections · 1 min").assertIsDisplayed()
        compose.onNode(hasText("Opening")).performClick()

        assertThat(actions.asked).containsExactly("goToSection 0")
        compose.onNodeWithTag(ChaptersSheetTag).assertDoesNotExist()
    }

    @Test
    fun `stop listening stops and closes the panel`() {
        show()

        compose.onNodeWithText("Stop listening").performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("stop")
        assertThat(dismissals).isEqualTo(1)
    }

    @Test
    fun `the grip closes the panel`() {
        show()

        compose.onNodeWithTag(SheetGripTag).performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()

        assertThat(dismissals).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the panel and its sections sheet`() {
        show(PlayerSamples.narration.copy(speed = 1.25f))

        compose.onNodeWithText("Section 2 sur 2 · 76\u00A0% du livre").assertIsDisplayed()
        compose.onNode(hasText("1,25×") and hasText("Vitesse")).assertIsDisplayed()
        compose.onNode(hasText("Sommeil")).assertIsDisplayed()
        compose.onNodeWithText("Arrêter l’écoute").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Reculer de 15 secondes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Phrase précédente").assertIsDisplayed()
        open(ChaptersButtonTag)
        compose.onNodeWithText("2 sections · 1 min").assertIsDisplayed()
        compose.onNode(hasText("Sections") and isHeading()).assertIsDisplayed()
    }
}
