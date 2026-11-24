package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoBarTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()

    private var undo: UndoUi by mutableStateOf(UndoUi.Highlight(MarkId("h1")))

    private fun show(rtl: Boolean = false) {
        compose.setContent {
            LirelleTheme {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ReaderChromeTheme(ReaderTheme.Light.colours(systemDark = false)) {
                        UndoBar(undo, actions::undo, actions::dismissUndo)
                    }
                }
            }
        }
    }

    @Test
    fun `a deleted highlight, and undo`() {
        show()

        compose.onNodeWithText("Highlight deleted").assertExists()
        compose.onNodeWithText("Undo").performClick()

        assertThat(actions.calls).containsExactly("undo")
    }

    @Test
    fun `removed bookmarks are counted`() {
        undo = UndoUi.Bookmarks(listOf(MarkId("b1")))
        show()
        compose.onNodeWithText("Bookmark deleted").assertExists()

        undo = UndoUi.Bookmarks(listOf(MarkId("b1"), MarkId("b2"), MarkId("b3")))
        compose.onNodeWithText("3 bookmarks deleted").assertExists()
    }

    @Test
    fun `in a right-to-left language undo is at the start of the line, on the left`() {
        show(rtl = true)

        val message = compose.onNodeWithText("Highlight deleted").getUnclippedBoundsInRoot()
        val button = compose.onNodeWithText("Undo").getUnclippedBoundsInRoot()
        assertThat(button.right.value).isAtMost(message.left.value)
    }

    @Test
    fun `the offer goes away after a short while`() {
        compose.mainClock.autoAdvance = false
        show()

        compose.mainClock.advanceTimeBy(3_800)
        assertThat(actions.calls).isEmpty()

        compose.mainClock.advanceTimeBy(400)
        assertThat(actions.calls).containsExactly("dismissUndo(${UndoUi.Highlight(MarkId("h1"))})")
    }

    @Test
    fun `a newer deletion is given the whole while again`() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(3_000)

        undo = UndoUi.Highlight(MarkId("h2"))
        // The change reaches the composition at the next frame, not when the clock is moved by hand.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(3_000)
        assertThat(actions.calls).isEmpty()

        compose.mainClock.advanceTimeBy(1_200)
        assertThat(actions.calls).containsExactly("dismissUndo(${UndoUi.Highlight(MarkId("h2"))})")
    }
}
