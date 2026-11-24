package app.tfdn.lirelle.core.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs

/*
 * The rule these helpers enforce: no line of text overflows, is clipped or breaks in the middle
 * of a word, whatever the system text size. A label in a fixed-size slot (a navigation item, a
 * button) stays on one line and ends with an ellipsis when it does not fit; any other text may
 * wrap, between words only. The label of a button that grows with it, named by the test, wraps on
 * two lines at most and is whole.
 *
 * A text scrolled out of view is not cut: what a scrolling column hides at its top and bottom, and
 * what a row that scrolls sideways hides at its sides, the user can bring into view. Such a text
 * must still be whole once there: laid out within its own edges, not cut short, not cut by
 * anything other than the scrolling container, and no larger than what the container shows.
 */

/** One text on screen, with the layout it was actually drawn with. */
class LaidOutText internal constructor(
    private val node: SemanticsNode,
    private val layout: TextLayoutResult,
) {
    val text: String get() = layout.layoutInput.text.text
    val lineCount: Int get() = layout.lineCount

    /** Whether the text is cut short and ends with an ellipsis. */
    val isEllipsized: Boolean get() = (0 until lineCount).any(layout::isLineEllipsized)

    /** Whether the text sits in a navigation item or a button: a slot that does not grow with it. */
    val isLabel: Boolean
        get() = node.ancestors().any { it.config.getOrNull(SemanticsProperties.Role) in LabelRoles }

    /** Whether a line is drawn past the edges of the text itself, where it is clipped or covers a neighbour. */
    internal val spills: Boolean
        get() {
            // Not `didOverflowWidth`: it compares with the width offered, which a centred text does not fill.
            val widestLine = (0 until lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            return widestLine > layout.size.width + Tolerance ||
                layout.multiParagraph.height > layout.size.height + Tolerance
        }

    /** The first word cut in two by a line break, or null when lines only break between words. */
    internal val wordBrokenByLineBreak: String?
        get() = (0 until lineCount - 1)
            .map { layout.getLineEnd(it) }
            .firstOrNull { it in 1 until text.length && text[it - 1].isLetterOrDigit() && text[it].isLetterOrDigit() }
            ?.let { end ->
                text.substring(0, end).takeLastWhile(Char::isLetterOrDigit) + "|" +
                    text.substring(end).takeWhile(Char::isLetterOrDigit)
            }

    /** The bounds of the text, ignoring any clipping, in the coordinates of its root. */
    val boundsInRoot: Rect get() = Rect(node.positionInRoot, node.size.toSize())

    /** Whether all of the text can be seen right now, nothing of it being cut away or scrolled out of view. */
    val isWhollyShown: Boolean get() = node.boundsInRoot == boundsInRoot

    /**
     * What an ancestor or the edge of the screen cuts away, or null when the text can be seen whole
     * or brought whole into view by scrolling.
     */
    internal val clipping: String?
        get() {
            val whole = boundsInRoot
            val shown = node.boundsInRoot
            val row = node.scrollingRow()
            val cutSideways = when {
                row != null -> !row.canShowWhole(whole, shown)
                // Wholly outside a scrolling column for now: only the screen can cut it once scrolled to.
                shown.isEmpty ->
                    whole.left < -Tolerance ||
                        whole.right > (node.ancestors().lastOrNull() ?: node).size.width + Tolerance
                else -> shown.left > whole.left + Tolerance || shown.right < whole.right - Tolerance
            }
            // Scrolled wholly out of its row, nothing of the text is shown to compare with: what is
            // shown of the row tells whether the text will be cut at its top or bottom once in view.
            val upright = if (shown.isEmpty && row != null) row.boundsInRoot else shown
            val cutUpright = upright.top > whole.top + Tolerance || upright.bottom < whole.bottom - Tolerance
            // What a scrolling column cuts at its top and bottom is reachable by scrolling.
            val scrollsVertically = node.ancestors().any { SemanticsProperties.VerticalScrollAxisRange in it.config }
            return if (cutSideways || (cutUpright && !scrollsVertically)) "drawn $shown of $whole" else null
        }

    override fun toString(): String = "\"$text\""
}

/** Every text on screen except what the user typed in a field, dialogs included. */
fun SemanticsNodeInteractionsProvider.laidOutTexts(): List<LaidOutText> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .filterNot { SemanticsProperties.EditableText in it.config }
        .map { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            LaidOutText(node, results.single())
        }

/**
 * Asserts the text rule on everything on screen.
 *
 * @param unbreakable values with no place to break, such as a URL or an e-mail address: these
 *   may wrap anywhere. Everything else must break between words only.
 * @param wrappingLabels labels of buttons that grow with them: these are whole, on two lines at most, broken
 *   between words. Every other label is one line.
 * @return the texts checked, so a test can assert on the ones it expects.
 */
fun SemanticsNodeInteractionsProvider.assertAllTextFits(
    unbreakable: Set<String> = emptySet(),
    wrappingLabels: Set<String> = emptySet(),
): List<LaidOutText> {
    val texts = laidOutTexts()
    assertWithMessage("texts on screen").that(texts).isNotEmpty()
    texts.forEach { it.assertFits(unbreakable, wrappingLabels) }
    return texts
}

private fun LaidOutText.assertFits(unbreakable: Set<String>, wrappingLabels: Set<String>) {
    if (isLabel && text !in wrappingLabels) {
        assertWithMessage("lines of the label $this").that(lineCount).isEqualTo(1)
    } else {
        if (isLabel) assertWithMessage("lines of the label $this").that(lineCount).isAtMost(WrappingLabelLines)
        if (text !in unbreakable) {
            assertWithMessage("$this is cut short").that(isEllipsized).isFalse()
            assertWithMessage("word of $this broken by a line break").that(wordBrokenByLineBreak).isNull()
        }
    }
    assertWithMessage("$this is drawn past its own edges").that(spills).isFalse()
    assertWithMessage("clipping of $this").that(clipping).isNull()
}

/**
 * The row scrolling sideways that holds the node, or null. Such a row is one strip of content
 * moved behind a window: what is out of the window is reached by scrolling. A pager or a lazy
 * row is not one: what sticks out of a page or of an item is cut for good.
 */
private fun SemanticsNode.scrollingRow(): SemanticsNode? = ancestors()
    .firstOrNull { SemanticsProperties.HorizontalScrollAxisRange in it.config }
    ?.takeUnless { SemanticsProperties.IndexForKey in it.config || SemanticsActions.ScrollToIndex in it.config }

/**
 * Whether this row that scrolls sideways can show the whole of a text it holds, and is the only
 * thing cutting it now.
 *
 * @param whole the bounds of the text, ignoring any clipping.
 * @param shown what is drawn of it at the moment.
 */
private fun SemanticsNode.canShowWhole(whole: Rect, shown: Rect): Boolean {
    val range = config[SemanticsProperties.HorizontalScrollAxisRange]
    val window = Rect(positionInRoot, size.toSize())
    // How far the content can still move each way, in the coordinates of the root.
    val scrolled = range.value()
    val remaining = range.maxValue() - scrolled
    val leftward = layoutInfo.layoutDirection == LayoutDirection.Rtl != range.reverseScrolling
    val hiddenLeft = if (leftward) remaining else scrolled
    val hiddenRight = if (leftward) scrolled else remaining
    val reachable = whole.left >= window.left - hiddenLeft - Tolerance &&
        whole.right <= window.right + hiddenRight + Tolerance

    // What is seen of the row itself: nothing when it is out of a scrolling column for now.
    val seen = boundsInRoot.takeUnless { it.isEmpty }
    val wideEnough = whole.width <= (seen ?: window).width + Tolerance

    // The part of the text inside the window is drawn, all of it: nothing else cuts the text.
    val left = maxOf(whole.left, (seen ?: window).left)
    val right = minOf(whole.right, (seen ?: window).right)
    val cutByRowOnly = when {
        seen == null || right <= left -> shown.isEmpty
        else -> !shown.isEmpty && abs(shown.left - left) <= Tolerance && abs(shown.right - right) <= Tolerance
    }
    return reachable && wideEnough && cutByRowOnly
}

private fun SemanticsNode.ancestors(): Sequence<SemanticsNode> = generateSequence(parent) { it.parent }

private val LabelRoles = setOf(Role.Button, Role.Tab)

private const val WrappingLabelLines = 2

/** Slack for rounding to whole pixels. */
private const val Tolerance = 1f
