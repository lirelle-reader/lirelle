package app.tfdn.lirelle.feature.player.narration

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.player.PlayerSamples
import app.tfdn.lirelle.feature.player.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the narration's bars, panel, strip or notice overflows or is clipped, at any width, language or size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class NarrationTextFitTest(private val screen: String, private val language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Everything as long as it gets: the section's title, its times, the speed, a timer, the skips. */
    private val longest = PlayerSamples.narration.copy(
        sectionTitle = LongSection,
        sectionNumber = 148,
        sectionCount = 148,
        elapsedMs = 9 * 60 * 60_000L + 59 * 60_000L,
        sectionDurationMs = 19 * 60 * 60_000L,
        bookPercent = 99.6,
        speed = 2.75f,
        sleep = SleepState(SleepChoice.Minutes(45), remainingMs = 44 * 60_000L, fading = false),
        settings = PlayerSettings(backSeconds = 60, forwardSeconds = 60),
    )

    private val actions = RecordingNarrationActions()

    @Test
    fun `the bar with a long section title`() {
        val texts = show { NarrationBar(longest, LightNarrationColors, actions, onOpenPanel = {}, wide = false) }

        // The title keeps to one line, cut short on a phone.
        val title = texts.single { it.text == LongSection }
        assertWithMessage("lines of $title").that(title.lineCount).isEqualTo(1)
        if (screen != Wide) assertWithMessage("$title is cut short").that(title.isEllipsized).isTrue()
    }

    @Test
    fun `the bar with a long section title, at 0,5x`() {
        show { NarrationBar(longest.copy(speed = 0.5f), LightNarrationColors, actions, onOpenPanel = {}, wide = false) }
    }

    @Test
    fun `the wide bar with a long section title`() {
        show { NarrationBar(longest, LightNarrationColors, actions, onOpenPanel = {}, wide = true) }
    }

    @Test
    fun `the strip with both ways back`() {
        show { NarrationStrip(LightNarrationColors, onBack = {}, onReadPage = {}) }
    }

    @Test
    fun `the strip with the way back alone`() {
        show { NarrationStrip(LightNarrationColors, onBack = {}, onReadPage = null) }
    }

    @Test
    fun `the panel with a long section title and a timer`() {
        val texts = show { NarrationPanel(longest, LightNarrationColors, actions, onDismiss = {}) }

        // The title takes the lines it needs.
        val title = texts.single { it.text == LongSection }
        assertWithMessage("$title is cut short").that(title.isEllipsized).isFalse()
    }

    @Test
    fun `the panel offering to go back`() {
        val texts = show { NarrationPanel(longest.copy(goBackShown = true), LightNarrationColors, actions) {} }

        val back = texts.single { it.text == compose.activity.getString(R.string.player_go_back) }
        assertWithMessage("lines of $back").that(back.lineCount).isEqualTo(1)
    }

    @Test
    fun `the notice of a section with a long title`() {
        show { NarrationNotice(SkippedNotice(BookId("42"), 2, LongSection), LightNarrationColors, {}, {}) }
    }

    @Test
    fun `the notice of a section without a title`() {
        show { NarrationNotice(SkippedNotice(BookId("42"), 2, null), LightNarrationColors, {}, {}) }
    }

    @Test
    fun `the ended bar`() {
        show { NarrationEndedBar(LightNarrationColors, onFinishBook = {}) }
    }

    private fun show(content: @Composable () -> Unit): List<LaidOutText> {
        compose.setContent { LirelleTheme(darkTheme = false) { Column { content() } } }
        // The ended bar's second line is meant to be cut: one line, then an ellipsis.
        val rest = if (language == "fr") RestFrench else RestEnglish
        val texts = compose.assertAllTextFits(unbreakable = setOf(rest))
        // Only the section's title in the bar and that line may be cut short: times, buttons and messages are whole.
        texts.filterNot { it.text == LongSection || it.text == rest }.forEach {
            assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse()
        }
        return texts
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val RestEnglish = "The rest of the book has no narration"
        private const val RestFrench = "La suite du livre n’est pas narrée"
        private const val LongSection =
            "The section whose title is a whole sentence about the crossing of the strait at night in the storm"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
