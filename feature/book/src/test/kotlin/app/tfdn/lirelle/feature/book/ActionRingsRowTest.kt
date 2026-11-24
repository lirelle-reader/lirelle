package app.tfdn.lirelle.feature.book

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * The line of the two reading actions, at every width, language and text size: no word is cut, and the buttons
 * stack only where a word cannot be whole in half the line.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ActionRingsRowTest(private val screen: Int, private val language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${screen}dp-h800dp", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The pairs of buttons the page can show, the longest statuses among them. */
    private val pairs = listOf(
        ring(ActionKind.Read, RingStatus.ResumeAt(100), narrated = true) to ring(ActionKind.Listen, RingStatus.Start),
        ring(ActionKind.Read, RingStatus.Start) to ring(ActionKind.Listen, RingStatus.ResumeAt(100)),
        ring(ActionKind.Read, RingStatus.Again) to ring(ActionKind.Listen, RingStatus.Again),
        ring(ActionKind.Read, RingStatus.Resume) to ring(ActionKind.Listen, RingStatus.None),
        ring(ActionKind.Read, RingStatus.ResumeAt(62)) to ring(ActionKind.Listen, RingStatus.Offline, enabled = false),
        ring(ActionKind.Read, RingStatus.Unavailable, enabled = false) to ring(ActionKind.Listen, RingStatus.Resume),
    )

    private fun ring(kind: ActionKind, status: RingStatus, enabled: Boolean = true, narrated: Boolean = false) =
        ActionRingUi(kind, enabled, status, ringFraction(status), narrated, filled = enabled && kind == ActionKind.Read)

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().let {
        Rect(it.positionInRoot, Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }

    private fun List<LaidOutText>.within(area: Rect) = filter { area.contains(it.boundsInRoot.center) }

    @Test
    fun `no word is cut, and the buttons stack only where a word cannot be whole in half the line`() {
        compose.setContent {
            LirelleTheme {
                Surface {
                    // The padding of the book's page at its sides.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                        pairs.forEachIndexed { index, (read, listen) ->
                            ActionRingsRow(
                                listOf(RingAction(read, "read-$index") {}, RingAction(listen, "listen-$index") {}),
                            )
                        }
                    }
                }
            }
        }

        val texts = compose.assertAllTextFits()

        // Half the line, less the gap between the two buttons and the padding of a button.
        val column = (screen - 2 * 16 - 8) / 2f - 2 * 4
        pairs.indices.forEach { index ->
            val read = bounds("read-$index")
            val listen = bounds("listen-$index")
            val words = texts.within(read) + texts.within(listen)
            words.forEach { assertWithMessage("$it of the pair $index is cut short").that(it.isEllipsized).isFalse() }
            val widest = words.maxOf { it.boundsInRoot.width }
            val stacked = listen.top >= read.bottom
            // The line keeps one pixel of margin, a measured width being rounded: a word as wide as the column
            // stacks, as "Réécouter ·" does at 320 dp with the text twice its size.
            assertWithMessage("the pair $index is stacked, its widest word being $widest in a column of $column")
                .that(stacked)
                .isEqualTo(widest + 1 > column)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(320, 411, 1280).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 1.3f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
