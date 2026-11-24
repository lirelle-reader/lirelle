package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.text.TextAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val SlotTag = "slot"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionBarTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()

    private val dictionary = TextAction.App("A dictionary", "org.example.dictionary", "org.example.Define")
    private val notebook = TextAction.App("Notebook", "org.example.notebook", "org.example.Take")

    /** Everything a device can offer, in the order of the bar. */
    private val everything = listOf(
        TextAction.Translate,
        TextAction.Define,
        TextAction.Copy,
        TextAction.Share,
        TextAction.SearchWeb,
        dictionary,
        notebook,
    )

    private var selection by mutableStateOf(selectionAt(top = 0.6, bottom = 0.7))

    private fun string(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun selectionAt(top: Double, bottom: Double, left: Double = 0.2, right: Double = 0.8) = SelectionUi(
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:20)",
        text = "a passage of the book",
        sectionIndex = 1,
        bounds = PageBounds(left = left, top = top, right = right, bottom = bottom),
        exactId = null,
    )

    /** The bar over a page slot of the whole screen, or of [width] only. */
    private fun show(
        offered: List<TextAction> = everything,
        current: String? = null,
        width: Dp? = null,
        rtl: Boolean = false,
    ) {
        compose.setContent {
            LirelleTheme {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ReaderChromeTheme(ReaderTheme.Dark.colours(systemDark = true)) {
                        // A slot wider than the screen may stick out of it on the right.
                        val size = if (width == null) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(width).fillMaxHeight()
                        }
                        Box(size.testTag(SlotTag)) {
                            SelectionBar(selection, offered, current, actions)
                        }
                    }
                }
            }
        }
    }

    private fun spoken(colour: HighlightColour) = string(
        when (colour) {
            HighlightColour.Yellow -> R.string.reader_highlight_yellow
            HighlightColour.Green -> R.string.reader_highlight_green
            HighlightColour.Blue -> R.string.reader_highlight_blue
            HighlightColour.Pink -> R.string.reader_highlight_pink
        },
    )

    private fun bar() = compose.onNodeWithTag(SelectionBarTag).getUnclippedBoundsInRoot()

    private fun slot() = compose.onNodeWithTag(SlotTag).getUnclippedBoundsInRoot()

    private fun more() = compose.onNodeWithContentDescription(string(R.string.reader_selection_more))

    // Where

    @Test
    fun `the bar is above the selection, close to it`() {
        show()

        val top = slot().height * 0.6f
        assertThat(bar().bottom.value).isAtMost(top.value)
        assertThat(bar().bottom.value).isAtLeast(top.value - 16f)
    }

    @Test
    fun `the bar goes below the selection when there is no room above, clear of the handles`() {
        selection = selectionAt(top = 0.02, bottom = 0.08)
        show()

        val bottom = slot().height * 0.08f
        assertThat(bar().top.value).isAtLeast(bottom.value + 24f)
    }

    @Test
    fun `the bar goes below once the room above is less than its height and the gap`() {
        show()
        val height = bar().height
        val slotHeight = slot().height

        // 4 dp short of the bar and its 8 dp gap above the selection.
        val tight = ((height + 4.dp) / slotHeight).toDouble()
        selection = selectionAt(top = tight, bottom = tight + 0.05)
        compose.waitForIdle()
        assertThat(bar().top.value).isAtLeast((slotHeight * (tight.toFloat() + 0.05f)).value + 24f)

        // 4 dp to spare.
        val roomy = ((height + 12.dp) / slotHeight).toDouble()
        selection = selectionAt(top = roomy, bottom = roomy + 0.05)
        compose.waitForIdle()
        assertThat(bar().bottom.value).isAtMost((slotHeight * roomy.toFloat()).value)
    }

    @Test
    fun `a selection over the whole page has the bar in its middle, clear of both handles`() {
        selection = selectionAt(top = 0.0, bottom = 1.0, left = 0.0, right = 1.0)
        show()

        val middle = (bar().top + bar().bottom) / 2
        assertThat(middle.value).isWithin(1f).of((slot().height / 2).value)
    }

    @Test
    fun `a selection nearly as tall as the page keeps the bar inside the page`() {
        selection = selectionAt(top = 0.0, bottom = 0.96)
        show()

        assertThat(bar().top.value).isAtLeast(slot().top.value)
        assertThat(bar().bottom.value).isAtMost(slot().bottom.value + 1f)
    }

    @Test
    fun `the bar stays inside the page sideways, near a selection at an edge`() {
        selection = selectionAt(top = 0.5, bottom = 0.55, left = 0.9, right = 1.0)
        show(width = 600.dp)

        assertThat(bar().left.value).isAtLeast(slot().left.value)
        assertThat(bar().right.value).isAtMost(slot().right.value)
    }

    @Test
    fun `on a wide page the bar is centred on the selection`() {
        selection = selectionAt(top = 0.5, bottom = 0.55, left = 0.3, right = 0.5)
        show(width = 900.dp)

        val centre = (bar().left + bar().right) / 2
        assertThat(centre.value).isWithin(1f).of(slot().left.value + 900f * 0.4f)
    }

    @Test
    fun `the bar is announced as a pane when it appears`() {
        show()

        val title = compose.onNodeWithTag(SelectionBarTag).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
        assertThat(title).isEqualTo(string(R.string.reader_selection_pane))
    }

    // Colours

    @Test
    fun `each colour dot is said by its name and makes a highlight in its colour`() {
        show()
        val dots = mapOf(
            R.string.reader_highlight_yellow to "#FACC15",
            R.string.reader_highlight_green to "#4ADE80",
            R.string.reader_highlight_blue to "#38BDF8",
            R.string.reader_highlight_pink to "#F472B6",
        )

        dots.forEach { (spoken, _) -> compose.onNodeWithContentDescription(string(spoken)).performClick() }

        assertThat(actions.calls).containsExactlyElementsIn(dots.values.map { "highlightSelection($it)" }).inOrder()
    }

    @Test
    fun `the current colour is marked`() {
        show(current = HighlightColour.Green.hex)

        compose.onNodeWithContentDescription(string(R.string.reader_highlight_green)).assertIsSelected()
        compose.onNodeWithContentDescription(string(R.string.reader_highlight_yellow)).assertIsNotSelected()
    }

    @Test
    fun `without an exact highlight under the selection no colour is marked`() {
        show(current = null)

        HighlightColour.entries.forEach { colour ->
            compose.onNode(hasContentDescription(spoken(colour))).assertIsNotSelected()
        }
    }

    // Actions

    @Test
    fun `note, translate, define and search are in that order, then more`() {
        show()

        val lefts = listOf(
            R.string.reader_selection_note,
            R.string.reader_selection_translate,
            R.string.reader_selection_define,
            R.string.reader_selection_search,
        ).map { compose.onNodeWithText(string(it)).getUnclippedBoundsInRoot().left.value }
        assertThat(lefts).isInStrictOrder()
        assertThat(more().getUnclippedBoundsInRoot().left.value).isGreaterThan(lefts.last())
    }

    @Test
    fun `in a right-to-left language the buttons run from the right, and more is on the left`() {
        show(rtl = true)

        val note = compose.onNodeWithText(string(R.string.reader_selection_note)).getUnclippedBoundsInRoot()
        val translate = compose.onNodeWithText(string(R.string.reader_selection_translate)).getUnclippedBoundsInRoot()
        assertThat(note.left.value).isGreaterThan(translate.left.value)
        assertThat(more().getUnclippedBoundsInRoot().right.value).isAtMost(translate.left.value)
    }

    @Test
    fun `each button calls its action`() {
        show()

        listOf(
            R.string.reader_selection_note,
            R.string.reader_selection_translate,
            R.string.reader_selection_define,
            R.string.reader_selection_search,
        ).forEach { compose.onNodeWithText(string(it)).performClick() }

        assertThat(actions.calls).containsExactly(
            "noteOnSelection",
            "selectionAction(Translate)",
            "selectionAction(Define)",
            "searchSelection",
        ).inOrder()
    }

    @Test
    fun `translate and define are absent when no app offers them`() {
        show(offered = everything - TextAction.Translate - TextAction.Define)

        compose.onNodeWithText(string(R.string.reader_selection_translate)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_selection_define)).assertDoesNotExist()
        more().performClick()
        compose.onNodeWithText(string(R.string.reader_selection_translate)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_selection_define)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_selection_note)).assertExists()
        compose.onNodeWithText(string(R.string.reader_selection_search)).assertExists()
    }

    @Test
    fun `more lists copy, share, search the web, then the other apps under their names`() {
        show()

        more().performClick()

        val tops = listOf(
            string(R.string.reader_selection_copy),
            string(R.string.reader_selection_share),
            string(R.string.reader_selection_search_web),
            dictionary.label,
            notebook.label,
        ).map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().top.value }
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `each entry of more calls its action and closes the menu`() {
        show()
        val entries = listOf(
            string(R.string.reader_selection_copy),
            string(R.string.reader_selection_share),
            string(R.string.reader_selection_search_web),
            dictionary.label,
        )

        entries.forEach { entry ->
            more().performClick()
            compose.onNodeWithText(entry).performClick()
            compose.onNodeWithText(entry).assertDoesNotExist()
        }

        assertThat(actions.calls).containsExactly(
            "selectionAction(Copy)",
            "selectionAction(Share)",
            "selectionAction(SearchWeb)",
            "selectionAction($dictionary)",
        ).inOrder()
    }

    // Too narrow for all of them

    @Test
    fun `what does not fit goes whole to the top of more, in its order`() {
        // The narrowest window of a split screen.
        show(width = 220.dp)
        val bar = bar()
        HighlightColour.entries.forEach { colour ->
            val dot = compose.onNodeWithContentDescription(spoken(colour)).getUnclippedBoundsInRoot()
            assertWithMessage("$colour dot").that(dot.left.value).isAtLeast(bar.left.value)
            assertWithMessage("$colour dot").that(dot.right.value).isAtMost(bar.right.value)
            assertWithMessage("$colour dot").that(dot.width.value).isAtLeast(48f)
        }
        val buttons = listOf(
            R.string.reader_selection_note to "noteOnSelection",
            R.string.reader_selection_translate to "selectionAction(Translate)",
            R.string.reader_selection_define to "selectionAction(Define)",
            R.string.reader_selection_search to "searchSelection",
        )

        val texts = compose.assertAllTextFits()
        val shown = buttons.filter { (label, _) -> texts.any { it.text == string(label) } }
        texts.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
        assertWithMessage("buttons shown").that(shown).isNotEmpty()
        assertWithMessage("buttons shown").that(shown).isNotEqualTo(buttons)
        assertWithMessage("buttons shown").that(shown).isEqualTo(buttons.take(shown.size))

        more().performClick()
        val hidden = buttons.drop(shown.size).map { string(it.first) }
        val tops = (hidden + string(R.string.reader_selection_copy))
            .map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().top.value }
        assertThat(tops).isInStrictOrder()

        compose.onNodeWithText(hidden.last()).performClick()
        assertThat(actions.calls).containsExactly(buttons.last().second)
    }
}
