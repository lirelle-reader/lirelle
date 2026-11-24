package app.tfdn.lirelle.core.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The sheet that a list inside never moves: it is closed by its grip, a tap above it, back and a
 * screen reader's dismiss action, and nothing the list does moves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class GripSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)
    private var dismissed = 0

    private val sheet get() = compose.onNodeWithTag(GripSheetTag)
    private val grip get() = compose.onNodeWithTag(SheetGripTag)
    private val list get() = compose.onNodeWithTag(ListTag)

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun show() {
        compose.setContent {
            LirelleTheme {
                if (open) {
                    GripSheet(onDismissRequest = {
                        dismissed++
                        open = false
                    }) {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .height(sheetListHeight())
                                .testTag(ListTag),
                        ) {
                            items((1..RowCount).toList()) { Text("Row $it", Modifier.height(52.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The sheet stays at [bounds], and open, over a few looks. */
    private fun assertStays(bounds: Rect) {
        repeat(Looks) { look ->
            compose.mainClock.advanceTimeBy(LookEveryMs)
            assertWithMessage("the sheet, look $look").that(sheet.bounds()).isEqualTo(bounds)
        }
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun `the grip dragged far enough down closes the sheet`() {
        show()

        grip.performTouchInput { swipeDown(startY = centerY, endY = centerY + LongDragPx, durationMillis = SlowMs) }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
        sheet.assertDoesNotExist()
    }

    @Test
    fun `the grip dragged a little comes back up`() {
        show()
        val before = sheet.bounds()

        grip.performTouchInput { swipeDown(startY = centerY, endY = centerY + ShortDragPx, durationMillis = SlowMs) }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(0)
        assertThat(sheet.bounds()).isEqualTo(before)
    }

    @Test
    fun `a fast fling of the list to its end leaves the sheet where it is`() {
        show()
        val before = sheet.bounds()

        repeat(Flings) { list.performTouchInput { swipeUp(durationMillis = FastMs) } }

        assertStays(before)
        compose.onNodeWithText("Row $RowCount").assertIsDisplayed()
    }

    @Test
    fun `a fast fling of the list back to its start leaves the sheet where it is`() {
        show()
        val before = sheet.bounds()
        list.performScrollToIndex(RowCount - 1)

        repeat(Flings) { list.performTouchInput { swipeDown(durationMillis = FastMs) } }

        assertStays(before)
        compose.onNodeWithText("Row 1").assertIsDisplayed()
    }

    @Test
    fun `a slow swipe down on the list at its start does not close the sheet`() {
        show()
        val before = sheet.bounds()

        list.performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = SlowMs) }

        assertStays(before)
    }

    @Test
    fun `a tap above the sheet closes it`() {
        show()
        // Material's scrim, over the window, under the sheet.
        compose.onNodeWithContentDescription("Close sheet")
            .performTouchInput { click(Offset(centerX, 10f)) }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `back closes the sheet`() {
        show()

        // The sheet is a dialog of its own: back goes to it.
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a screen reader closes the sheet with the grip's dismiss action`() {
        show()

        grip.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    private companion object {
        const val ListTag = "list"
        const val RowCount = 44
        const val Flings = 4
        const val FastMs = 60L
        const val SlowMs = 800L
        const val LongDragPx = 600f
        const val ShortDragPx = 60f
        const val Looks = 6
        const val LookEveryMs = 250L
    }
}
