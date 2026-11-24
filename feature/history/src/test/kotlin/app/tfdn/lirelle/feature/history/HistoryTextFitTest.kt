package app.tfdn.lirelle.feature.history

import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the history overflows, is clipped or breaks mid-word, at phone and tablet widths,
 * in both languages, at the default and at the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class HistoryTextFitTest(
    language: String,
    width: Int,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `the populated history fits`() {
        compose.setContent {
            LirelleTheme {
                HistoryScreen(
                    state = historyState(),
                    onSelectPeriod = {},
                    onToggleOutcome = {},
                    onLoadMore = {},
                    onOpenRead = { _, _ -> },
                    onBack = {},
                    onRetrySummary = {},
                    onRetryLog = {},
                )
            }
        }

        compose.assertAllTextFits()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 1280).flatMap { width ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(language, width, fontScale) }
                }
            }
    }
}
