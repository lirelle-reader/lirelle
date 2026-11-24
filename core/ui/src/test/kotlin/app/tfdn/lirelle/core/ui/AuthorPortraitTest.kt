package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.laidOutTexts
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.hypot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real glyph widths, needed to know whether the initials fit.
class AuthorPortraitTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `initials are the first letters of the first and last words`() {
        assertThat(initialsOf("Bruno Patino")).isEqualTo("BP")
        assertThat(initialsOf("Ursula K. Le Gray")).isEqualTo("UG")
        assertThat(initialsOf("  Agatha   Carter ")).isEqualTo("AC")
    }

    @Test
    fun `initials are upper-case, accents kept`() {
        assertThat(initialsOf("émile Zed")).isEqualTo("ÉZ")
        assertThat(initialsOf("bell hooks")).isEqualTo("BH")
    }

    @Test
    fun `a name of one word has one initial`() {
        assertThat(initialsOf("Homère")).isEqualTo("H")
    }

    @Test
    fun `a blank name has no initials`() {
        assertThat(initialsOf("")).isEmpty()
        assertThat(initialsOf("   ")).isEmpty()
    }

    @Test
    fun `a blank name composes no text`() {
        compose.setContent { LirelleTheme { AuthorPortrait(url = null, name = " ", modifier = Modifier.size(88.dp)) } }

        assertThat(compose.laidOutTexts()).isEmpty()
    }

    private fun showPortraits() {
        compose.setContent {
            LirelleTheme {
                Column {
                    Sizes.forEach { (tag, size) ->
                        // The widest pair of capitals there is.
                        AuthorPortrait(url = null, name = "Mary Wollstonecraft", Modifier.size(size).testTag(tag))
                    }
                }
            }
        }
    }

    private fun assertInitialsFitTheirCircles() {
        val texts = compose.assertAllTextFits()
        assertThat(texts.map { it.text }).containsExactly("MW", "MW", "MW")
        val circles = Sizes.map { (tag, _) ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            Rect(node.positionInRoot, node.size.toSize())
        }
        texts.forEach { text ->
            val box = text.boundsInRoot
            val circle = circles.single { it.contains(box.center) }
            assertWithMessage("lines of $text").that(text.lineCount).isEqualTo(1)
            assertWithMessage("$text is whole").that(text.isWhollyShown).isTrue()
            // The disc cuts what is outside it: every corner of the text is within the radius.
            val offCentre = hypot(box.center.x - circle.center.x, box.center.y - circle.center.y)
            val reach = hypot(box.width, box.height) / 2 + offCentre
            assertWithMessage("reach of $text in a circle of ${circle.width}px").that(reach).isAtMost(circle.width / 2)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `two initials fit the circle at every size it is used`() {
        showPortraits()

        assertInitialsFitTheirCircles()
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)
    fun `two initials fit the circle at the largest text size`() {
        showPortraits()

        assertInitialsFitTheirCircles()
    }

    private companion object {
        /** The author screen, a grid item on a phone, and a narrow grid item. */
        val Sizes = listOf("author-screen" to 88.dp, "grid-item" to 100.dp, "narrow-grid-item" to 72.dp)
    }
}
