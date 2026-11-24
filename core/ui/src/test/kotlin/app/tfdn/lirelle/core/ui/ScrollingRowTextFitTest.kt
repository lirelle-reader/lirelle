package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What `assertAllTextFits` accepts in a row that scrolls sideways: a text the user can scroll
 * into view is not cut. Everything else about that text, and every other text, is still checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ScrollingRowTextFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val words = listOf("First of the row", "Second of the row", "Third of the row", "Fourth of the row")

    @Composable
    private fun ScrollingRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        Row(modifier = modifier.horizontalScroll(rememberScrollState())) { content() }
    }

    private fun showRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        compose.setContent { ScrollingRow(modifier, content) }
    }

    private fun assertTextDoesNotFit(): AssertionError =
        assertThrows(AssertionError::class.java) { compose.assertAllTextFits() }

    private fun List<LaidOutText>.named(text: String) = single { it.text == text }

    @Test
    fun `a text scrolled out of a row that scrolls sideways fits`() {
        showRow { words.forEach { Text(it, modifier = Modifier.testTag(it), maxLines = 1, softWrap = false) } }

        val atStart = compose.assertAllTextFits()
        assertThat(atStart.named(words.first()).isWhollyShown).isTrue()
        assertThat(atStart.named(words.last()).isWhollyShown).isFalse()

        compose.onNodeWithTag(words.last()).performScrollTo()
        val atEnd = compose.assertAllTextFits()
        assertThat(atEnd.named(words.last()).isWhollyShown).isTrue()
        assertThat(atEnd.named(words.first()).isWhollyShown).isFalse()
    }

    @Test
    fun `a text cut short in a row that scrolls sideways does not fit`() {
        showRow { Text("A text cut short", Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("cut short")
    }

    @Test
    fun `a text clipped inside a row that scrolls sideways does not fit`() {
        showRow {
            Box(Modifier.width(60.dp).clipToBounds()) {
                Text("Clipped by a box", Modifier.offset(x = 30.dp), maxLines = 1, softWrap = false)
            }
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }

    @Test
    fun `a text cut at the top and bottom of a row that scrolls sideways does not fit`() {
        showRow(Modifier.height(6.dp)) {
            words.forEach { Text(it, Modifier.requiredHeight(40.dp), maxLines = 1, softWrap = false) }
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }

    @Test
    fun `a text wider than the row that scrolls sideways does not fit`() {
        showRow(Modifier.width(100.dp)) {
            Text("A text that can never be seen whole in so narrow a row", maxLines = 1, softWrap = false)
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }

    @Test
    fun `a text placed where the row cannot scroll to does not fit`() {
        showRow(Modifier.width(200.dp)) {
            Text("Past the end", Modifier.offset(x = 400.dp), maxLines = 1, softWrap = false)
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }

    @Test
    fun `a text past the edge of a page of a pager still does not fit`() {
        compose.setContent {
            HorizontalPager(state = rememberPagerState { 2 }) {
                Text("Past the edge of the page", Modifier.offset(x = 250.dp), maxLines = 1, softWrap = false)
            }
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }

    @Test
    fun `a text clipped where nothing scrolls still does not fit`() {
        compose.setContent {
            Box(Modifier.width(200.dp).clipToBounds()) {
                Text("Past the edge of the box", Modifier.offset(x = 150.dp), maxLines = 1, softWrap = false)
            }
        }

        assertThat(assertTextDoesNotFit()).hasMessageThat().contains("clipping")
    }
}
