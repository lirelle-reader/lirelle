package app.tfdn.lirelle.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the shell overflows, is clipped or breaks mid-word, on a narrow and an ordinary
 * phone (bottom bar) and on a tablet (rail), in both languages, from small to very large text.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ShellTextFitTest(
    private val screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        val width = if (screen == Tablet) WindowWidthClass.Medium else WindowWidthClass.Compact
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    Shell(
                        account = {},
                        home = { stubHome(it) },
                        library = { stubLibrary(it) },
                        search = { stubSearch(it) },
                    )
                }
            }
        }
    }

    private fun itemBounds(destination: TopLevelDestination): DpRect =
        compose.onNodeWithTag(destination.testTag).getUnclippedBoundsInRoot()

    @Test
    fun `every navigation label stays on one line inside its item`() {
        show()

        val labels = compose.assertAllTextFits().filter { it.isLabel }

        assertThat(labels).hasSize(TopLevelDestination.entries.size)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        with(compose.density) {
            TopLevelDestination.entries.zip(labels).forEach { (destination, label) ->
                val item = itemBounds(destination)
                val text = label.boundsInRoot
                assertWithMessage("$label is inside its item, sideways")
                    .that(text.left >= item.left.toPx() && text.right <= item.right.toPx()).isTrue()
                assertWithMessage("$label is inside its item, upright")
                    .that(text.top >= item.top.toPx() && text.bottom <= item.bottom.toPx()).isTrue()
                assertWithMessage("the item of $label is on screen")
                    .that(item.left >= root.left && item.right <= root.right && item.bottom <= root.bottom).isTrue()
            }
        }
    }

    @Test
    fun `every label has the same size`() {
        show()

        val heights = compose.assertAllTextFits().filter { it.isLabel }.map { it.boundsInRoot.height }

        assertThat(heights.distinct()).hasSize(1)
    }

    @Test
    fun `the text of every destination fits`() {
        show()

        TopLevelDestination.entries.forEach { destination ->
            compose.onNodeWithTag(destination.testTag).performClick()
            compose.assertAllTextFits()
        }
    }

    companion object {
        private const val NarrowPhone = "w320dp-h640dp-xhdpi"
        private const val Phone = "w411dp-h891dp-420dpi"
        private const val Tablet = "w700dp-h1000dp-xhdpi"

        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf(NarrowPhone, Phone, Tablet).flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(0.8f, 1.0f, 1.3f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
