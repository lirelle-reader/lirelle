package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val RowTag = "row"
private const val ActionTag = "action"
private const val PlainTag = "plain"
private const val Title = "Home"
private const val LongTitle = "A title that is far too long to stand on one line beside its action"

/** The title's row is the title's height with or without an action, which is centred on it at its end. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: heights are compared.
@Config(qualifiers = "w360dp-h640dp")
class ScreenTitleRowTest {

    @get:Rule
    val compose = createComposeRule()

    private var action by mutableStateOf<Dp?>(null)
    private var title by mutableStateOf(Title)
    private var roomBelow by mutableStateOf(Dp.Unspecified)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Column {
                        // What the screens had before the row: the title alone.
                        Text("Plain", Modifier.testTag(PlainTag), style = MaterialTheme.typography.headlineMedium)
                        ScreenTitleRow(title, Modifier.testTag(RowTag), roomBelow) {
                            action?.let { Box(Modifier.size(it).testTag(ActionTag)) }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun `without an action the row is the title, a heading, as high as its text`() {
        show()

        compose.onNodeWithText(Title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertThat(bounds(RowTag).height).isEqualTo(bounds(PlainTag).height)
        assertThat(bounds(RowTag).width).isEqualTo(360.dp)
    }

    @Test
    fun `an action taller than the title leaves the row's height alone, and is centred on the title at the end`() {
        show()
        val alone = bounds(RowTag)

        action = 48.dp

        val row = bounds(RowTag)
        val button = bounds(ActionTag)
        assertThat(row.height).isEqualTo(alone.height)
        assertThat(row.top).isEqualTo(alone.top)
        assertThat(button.right).isEqualTo(row.right)
        assertThat(button.height).isEqualTo(48.dp)
        assertThat(((button.top + button.bottom) / 2).value).isWithin(0.5f).of(((row.top + row.bottom) / 2).value)
        // It hangs out of the row, above and below.
        assertThat(button.top).isLessThan(row.top)
        assertThat(button.bottom).isGreaterThan(row.bottom)
    }

    @Test
    fun `an action smaller than the title is centred inside the row`() {
        action = 24.dp
        show()

        val row = bounds(RowTag)
        val button = bounds(ActionTag)
        assertThat(row.height).isEqualTo(bounds(PlainTag).height)
        assertThat(((button.top + button.bottom) / 2).value).isWithin(0.5f).of(((row.top + row.bottom) / 2).value)
    }

    @Test
    fun `with little room below, the action is moved up rather than hang past it`() {
        action = 48.dp
        roomBelow = 4.dp
        show()

        val row = bounds(RowTag)
        val button = bounds(ActionTag)
        assertThat(row.height).isEqualTo(bounds(PlainTag).height)
        assertThat(button.bottom.value).isWithin(0.5f).of((row.bottom + 4.dp).value)
        assertThat(button.height).isEqualTo(48.dp)
    }

    @Test
    fun `a long title wraps in the width the action leaves`() {
        action = 48.dp
        title = LongTitle
        show()

        val text = compose.onNodeWithText(LongTitle).getUnclippedBoundsInRoot()
        assertThat(text.right).isAtMost(bounds(ActionTag).left)
        assertThat(text.height).isGreaterThan(bounds(PlainTag).height)
        assertThat(bounds(RowTag).height).isEqualTo(text.height)
    }

    @Test
    fun `from right to left the action is at the left`() {
        action = 48.dp
        direction = LayoutDirection.Rtl
        show()

        assertThat(bounds(ActionTag).left).isEqualTo(bounds(RowTag).left)
        assertThat(compose.onNodeWithText(Title).getUnclippedBoundsInRoot().right).isEqualTo(bounds(RowTag).right)
    }
}
