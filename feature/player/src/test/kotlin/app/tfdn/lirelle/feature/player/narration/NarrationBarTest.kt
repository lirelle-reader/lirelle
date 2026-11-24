package app.tfdn.lirelle.feature.player.narration

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.PlayerSamples
import app.tfdn.lirelle.feature.player.SleepSheetTag
import app.tfdn.lirelle.feature.player.SpeedSheetTag
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The narration's bar, phone and tablet, its strip of ways back, its notice and its ended bar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NarrationBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingNarrationActions()
    private var panelsOpened = 0

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { LirelleTheme(darkTheme = false) { content() } }
    }

    /** The bar's texts, which open the panel. */
    private val texts = hasClickAction() and hasText("Section 4")

    private fun clickLabel(label: String) =
        SemanticsMatcher("clicking it does \"$label\"") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == label
        }

    /** Whether some pixel drawn by the node is [colour]. */
    private fun SemanticsNodeInteraction.drawsColour(colour: Color): Boolean {
        val pixels = captureToImage().toPixelMap()
        return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y] == colour } }
    }

    private fun showBar(state: NarrationUi = PlayerSamples.narration, wide: Boolean = false) = show {
        NarrationBar(state, LightNarrationColors, actions, onOpenPanel = { panelsOpened++ }, wide = wide)
    }

    @Test
    fun `the bar shows the section, its times and the speed, and each button asks for its control`() {
        showBar()

        compose.onNodeWithText("Section 4").assertIsDisplayed()
        listOf("0:04 ·", "−0:06 ·", "1×").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Previous sentence").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next sentence").performClick()

        assertThat(actions.asked).containsExactly("previousSentence", "playPause", "nextSentence").inOrder()
        // The phone's bar has neither skips nor speed.
        compose.onNodeWithContentDescription("Back 15 seconds").assertDoesNotExist()
    }

    @Test
    fun `at 2x the section's time left is the listening time`() {
        showBar(PlayerSamples.narration.copy(speed = 2f))

        listOf("0:04 ·", "−0:03 ·", "2×").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `paused, the round button plays`() {
        showBar(PlayerSamples.narration.copy(playing = false))

        compose.onNodeWithContentDescription("Play").performClick()

        assertThat(actions.asked).containsExactly("playPause")
    }

    @Test
    fun `a tap on the texts opens the panel, and a screen reader hears the texts and what the tap does`() {
        showBar()

        compose.onNode(texts).assert(clickLabel("Narration controls")).performClick()

        assertThat(panelsOpened).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the labels and the speed's decimal comma`() {
        showBar(PlayerSamples.narration.copy(speed = 1.25f))

        listOf("0:04 ·", "−0:04 ·", "1,25×").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Phrase précédente").assertIsDisplayed()
        compose.onNodeWithContentDescription("Phrase suivante").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNode(texts).assert(clickLabel("Commandes de la narration"))
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, paused, the round button says Lecture`() {
        showBar(PlayerSamples.narration.copy(playing = false))

        compose.onNodeWithContentDescription("Lecture").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the wide bar adds the skips, the speed and the sleep timer`() {
        showBar(PlayerSamples.narration.copy(speed = 1.25f), wide = true)

        compose.onNodeWithContentDescription("Back 15 seconds").performClick()
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        compose.onNodeWithContentDescription("Speed · 1.25×").performClick()
        compose.onNodeWithTag(SpeedSheetTag).assertIsDisplayed()
        compose.onNodeWithText("2×").performClick()

        assertThat(actions.asked).containsExactly("back", "forward", "setSpeed 2.0").inOrder()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the wide bar's moon opens the sleep timer`() {
        showBar(wide = true)

        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.onNodeWithTag(SleepSheetTag).assertIsDisplayed()
        compose.onNodeWithText("15 minutes").performClick()

        assertThat(actions.asked).containsExactly("startSleep Minutes(minutes=15)")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the wide bar's sleep timer speaks of the end of the section`() {
        showBar(wide = true)

        compose.onNodeWithContentDescription("Sleep timer").performClick()

        compose.onNodeWithText("End of section").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // The moon's colour is drawn.
    fun `while a timer runs, the moon is in the accent and says the time left`() {
        val sleep = SleepState(SleepChoice.Minutes(15), remainingMs = 600_000, fading = false)
        showBar(PlayerSamples.narration.copy(sleep = sleep), wide = true)

        val moon = compose.onNodeWithContentDescription("Sleep timer")
        moon.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "10 min"))
        assertThat(moon.drawsColour(LightNarrationColors.accent)).isTrue()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // The moon's colour is drawn.
    fun `without a timer, the moon is in the text colour and says nothing more`() {
        showBar(wide = true)

        val moon = compose.onNodeWithContentDescription("Sleep timer")
        moon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertThat(moon.drawsColour(LightNarrationColors.accent)).isFalse()
        assertThat(moon.drawsColour(LightNarrationColors.foreground)).isTrue()
    }

    @Test
    fun `a wide bar without the room for its extras is the phone's`() {
        showBar(wide = true)

        compose.onNodeWithContentDescription("Back 15 seconds").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next sentence").assertIsDisplayed()
    }

    @Test
    fun `the strip, while playing, offers the way back to the sentence alone`() {
        var backs = 0
        show { NarrationStrip(LightNarrationColors, onBack = { backs++ }, onReadPage = null) }

        compose.onNodeWithText("Back to the sentence").performClick()

        assertThat(backs).isEqualTo(1)
        compose.onNodeWithText("Read this page").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the widths decide.
    fun `the strip, paused, offers both ways side by side`() {
        val asked = mutableListOf<String>()
        show { NarrationStrip(LightNarrationColors, onBack = { asked += "back" }, onReadPage = { asked += "read" }) }

        compose.onNodeWithText("Read this page").performClick()
        compose.onNodeWithText("Back to the sentence").performClick()

        assertThat(asked).containsExactly("read", "back").inOrder()
        val back = compose.onNodeWithText("Back to the sentence").getBoundsInRoot()
        val read = compose.onNodeWithText("Read this page").getBoundsInRoot()
        assertThat(read.top).isEqualTo(back.top)
        assertThat(read.left).isGreaterThan(back.right)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the widths decide.
    fun `the strip's two ways stack where they do not fit side by side`() {
        show {
            Box(Modifier.width(240.dp)) {
                NarrationStrip(LightNarrationColors, onBack = {}, onReadPage = {})
            }
        }

        val back = compose.onNodeWithText("Back to the sentence").getBoundsInRoot()
        val read = compose.onNodeWithText("Read this page").getBoundsInRoot()
        assertThat(read.top).isAtLeast(back.bottom)
    }

    @Test
    fun `the notice names the section skipped, is told politely, and Read reads it`() {
        var reads = 0
        show { NarrationNotice(SkippedNotice(BookId("42"), 2, "Interlude"), LightNarrationColors, { reads++ }, {}) }

        compose.onNodeWithText("“Interlude” has no narration: it was skipped")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Read").performClick()

        assertThat(reads).isEqualTo(1)
    }

    @Test
    fun `a notice of a section without a title goes after 8 seconds`() {
        var timeouts = 0
        compose.mainClock.autoAdvance = false
        show { NarrationNotice(SkippedNotice(BookId("42"), 2, null), LightNarrationColors, {}, { timeouts++ }) }

        compose.onNodeWithText("A section without narration was skipped").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(7_900)
        assertThat(timeouts).isEqualTo(0)
        compose.mainClock.advanceTimeBy(200)
        assertThat(timeouts).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the notice and the strip`() {
        show {
            Column {
                NarrationNotice(SkippedNotice(BookId("42"), 2, "Interlude"), LightNarrationColors, {}, {})
                NarrationStrip(LightNarrationColors, onBack = {}, onReadPage = {})
            }
        }

        compose.onNodeWithText("«\u00A0Interlude\u00A0» n’a pas de narration\u00A0: elle a été passée")
            .assertIsDisplayed()
        compose.onNodeWithText("Lire").assertIsDisplayed()
        compose.onNodeWithText("Retour à la phrase").assertIsDisplayed()
        compose.onNodeWithText("Lire cette page").assertIsDisplayed()
    }

    @Test
    fun `the ended bar says so and finishes the book`() {
        var finishes = 0
        show { NarrationEndedBar(LightNarrationColors, onFinishBook = { finishes++ }) }

        compose.onNodeWithText("Narration finished").assertIsDisplayed()
        compose.onNodeWithText("The rest of the book has no narration").assertIsDisplayed()
        compose.onNodeWithText("Finish the book").performClick()

        assertThat(finishes).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the ended bar`() {
        show { NarrationEndedBar(LightNarrationColors, onFinishBook = {}) }

        compose.onNodeWithText("Narration terminée").assertIsDisplayed()
        compose.onNodeWithText("La suite du livre n’est pas narrée").assertIsDisplayed()
        compose.onNodeWithText("Terminer le livre").assertIsDisplayed()
    }
}
