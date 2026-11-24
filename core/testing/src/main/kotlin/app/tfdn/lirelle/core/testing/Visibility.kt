package app.tfdn.lirelle.core.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.toSize
import com.google.common.truth.Truth.assertWithMessage

/** Asserts that nothing of the node is cut away: not by an ancestor, a scrolling row or the screen. */
fun SemanticsNodeInteraction.assertIsWhollyShown(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val whole = Rect(node.positionInRoot, node.size.toSize())
    val shown = node.boundsInRoot
    assertWithMessage("what is shown of the node at $whole").that(shown).isEqualTo(whole)
    return this
}
