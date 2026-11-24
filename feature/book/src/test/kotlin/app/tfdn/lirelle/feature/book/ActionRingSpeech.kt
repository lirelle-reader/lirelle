package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import com.google.common.truth.Truth.assertWithMessage

/**
 * What a screen reader says of a reading action's button: its name, its status in its spoken form, and that
 * the reader can narrate the book.
 *
 * @param name `R.string.action_read` or `R.string.action_listen`.
 * @param status the spoken form of the status, with [args]; null for a button that says its name alone.
 */
internal fun Context.spokenAction(name: Int, status: Int? = null, vararg args: Any, narrated: Boolean = false): String =
    listOfNotNull(
        getString(name),
        status?.let { getString(it, *args) },
        getString(R.string.action_ring_narration).takeIf { narrated },
    ).joinToString()

/**
 * Asserts which buttons the actions of the book's page are, in their order, by their test tags: there is no
 * other one, such as the "Read and listen" the page once had.
 */
internal fun SemanticsNodeInteractionsProvider.assertActionButtons(vararg tags: String) {
    val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    val buttons = onAllNodes(isButton and hasAnyAncestor(hasTestTag("book-actions"))).fetchSemanticsNodes()
    assertWithMessage("the buttons of the actions")
        .that(buttons.map { it.config.getOrNull(SemanticsProperties.TestTag) })
        .containsExactly(*tags)
        .inOrder()
}

/** Asserts whether the icon of Read has the sound waves that say the reader can narrate the book. */
internal fun SemanticsNodeInteractionsProvider.assertReadIconNarrated(narrated: Boolean) {
    val waves = onAllNodesWithTag(ActionRingWavesTag, useUnmergedTree = true).fetchSemanticsNodes()
    assertWithMessage("sound waves on the icon of Read").that(waves).hasSize(if (narrated) 1 else 0)
}
