package app.tfdn.lirelle.feature.book

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of a reading action's button overflows or is clipped, at any width, language, text size or theme. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ActionRingTextFitTest(screen: String, language: String, fontScale: Float, private val dark: Boolean) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The statuses that take the most room, and those of a switched-off button. */
    private val longest = listOf(
        ring(ActionKind.Read, RingStatus.ResumeAt(100), narrated = true).copy(filled = true),
        ring(ActionKind.Listen, RingStatus.ResumeAt(100)),
        ring(ActionKind.Read, RingStatus.Again).copy(filled = true),
        ring(ActionKind.Listen, RingStatus.Again),
        ring(ActionKind.Read, RingStatus.Start),
        ring(ActionKind.Listen, RingStatus.Offline, enabled = false),
        ring(ActionKind.Read, RingStatus.Unavailable, enabled = false),
        ring(ActionKind.Listen, RingStatus.None, enabled = false),
    )

    private fun ring(kind: ActionKind, status: RingStatus, enabled: Boolean = true, narrated: Boolean = false) =
        ActionRingUi(kind, enabled, status, ringFraction(status), narrated)

    @Test
    fun `alone, the texts beside the disc, every word is whole`() {
        compose.setContent {
            Page {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    longest.forEach { ui ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ActionRing(ui, "ring", beside = true, onClick = {})
                        }
                    }
                }
            }
        }

        val texts = compose.assertAllTextFits()

        // A word is a label of one line, which the rule lets end with an ellipsis: here none does.
        texts.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    @Test
    fun `two side by side, each in half the width, no text spills or is clipped`() {
        compose.setContent {
            Page {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    longest.chunked(2).forEach { (first, second) ->
                        Row(Modifier.fillMaxWidth()) {
                            listOf(first, second).forEach { ui ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    ActionRing(ui, "ring", beside = false, onClick = {})
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.assertAllTextFits()
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        LirelleTheme(darkTheme = dark) {
            // The padding of the book's page at its sides.
            Surface { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() } }
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2} dark={3}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp", "w1280dp-h800dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).flatMap { scale ->
                    listOf(false, true).map { dark -> arrayOf<Any>(screen, language, scale, dark) }
                }
            }
        }
    }
}
