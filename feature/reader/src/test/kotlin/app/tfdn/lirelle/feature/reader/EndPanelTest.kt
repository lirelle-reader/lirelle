package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the panel at the end of the book shows, and what each tap asks for. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EndPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asked = mutableListOf<String>()

    private val actions = object : EndPanelActions {
        override fun setRating(stars: Int?) {
            asked += "rate $stars"
        }

        override fun closeEndPanel() {
            asked += "close"
        }

        override fun openNextVolume() {
            asked += "next"
        }

        override fun dismissEndPanel() {
            asked += "dismiss"
        }

        override fun deleteFromDevice() = Unit

        override fun keepOnDevice() = Unit
    }

    private val colours = ReaderTheme.Sepia.colours(systemDark = false)
    private val next = NextVolume(bookId = BookId("13"), title = "The Next One", seriesIndex = "3")

    private fun show(panel: EndPanel) {
        compose.setContent {
            LirelleTheme { EndPanelSheet(panel, title = "The Green Lighthouse", colours, actions) }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun stars(count: Int) = context.resources.getQuantityString(R.plurals.reader_end_stars, count, count)

    private fun star(stars: Int): SemanticsNodeInteraction = compose.onNodeWithContentDescription(stars(stars))

    /** The label wraps word by word: the button says it whole. */
    private fun close() = compose.onNodeWithContentDescription(text(R.string.reader_end_close))

    private fun view() = compose.onNodeWithText(text(R.string.reader_end_view))

    private fun progress() = compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))

    private fun SemanticsNodeInteraction.assertTakesATap() {
        val bounds = getUnclippedBoundsInRoot()
        assertThat(bounds.width.value).isAtLeast(MinTouchDp)
        assertThat(bounds.height.value).isAtLeast(MinTouchDp)
    }

    @Test
    fun `it says the book is finished, with its title, and asks for an optional rating`() {
        show(EndPanel())

        compose.onNodeWithText(text(R.string.reader_end_finished)).assertIsDisplayed()
        compose.onNodeWithText("The Green Lighthouse").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_end_rating)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_end_rating_failed)).assertDoesNotExist()
        assertThat(progress().fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the five stars are spoken with their number, and none is chosen at first`() {
        show(EndPanel())

        assertThat(stars(1)).isEqualTo("1 star")
        assertThat(stars(5)).isEqualTo("5 stars")
        (1..5).forEach { star(it).assertIsDisplayed().assertIsNotSelected() }
        compose.onNodeWithContentDescription("6 stars").assertDoesNotExist()
    }

    @Test
    fun `a tap on a star asks for that rating`() {
        show(EndPanel())

        (1..5).forEach { star(it).performClick() }

        assertThat(asked).containsExactly("rate 1", "rate 2", "rate 3", "rate 4", "rate 5").inOrder()
    }

    @Test
    fun `the star chosen is marked, a tap on it clears the rating, and another one changes it`() {
        show(EndPanel(rating = 3))

        star(3).assertIsSelected()
        star(2).assertIsNotSelected()
        star(4).assertIsNotSelected()

        star(3).performClick()
        star(5).performClick()
        star(1).performClick()

        assertThat(asked).containsExactly("rate null", "rate 5", "rate 1").inOrder()
    }

    @Test
    fun `the button that closes says that it closes the book`() {
        show(EndPanel())

        assertThat(text(R.string.reader_end_close)).isEqualTo("Close the book")
        close().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the button that closes says that it closes the book, in French`() {
        show(EndPanel())

        assertThat(text(R.string.reader_end_close)).isEqualTo("Fermer le livre")
        close().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `a tap above the sheet dismisses it, a tap on the sheet does not`() {
        show(EndPanel(rating = 4, nextVolume = next))

        compose.onNodeWithText("The Green Lighthouse").performClick()
        assertThat(asked).isEmpty()

        compose.onRoot().performTouchInput { click(Offset(width / 2f, 10f)) }
        assertThat(asked).containsExactly("dismiss")
    }

    @Test
    fun `the sheet has a grip at its top`() {
        show(EndPanel())

        val grip = compose.onNodeWithTag(EndPanelGripTag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val sheet = compose.onNodeWithTag(EndPanelSheetTag).getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText(text(R.string.reader_end_finished)).getUnclippedBoundsInRoot()
        assertThat(grip.top.value).isAtLeast(sheet.top.value)
        assertThat(grip.bottom.value).isAtMost(heading.top.value)
    }

    @Test
    fun `a swipe down on the sheet dismisses it`() {
        show(EndPanel(rating = 4, nextVolume = next))

        compose.onNodeWithTag(EndPanelSheetTag).performTouchInput { swipeDown() }

        assertThat(asked).containsExactly("dismiss")
    }

    @Test
    fun `a swipe down from the grip dismisses it`() {
        show(EndPanel())

        compose.onNodeWithTag(EndPanelGripTag).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 600f)
        }

        assertThat(asked).containsExactly("dismiss")
    }

    @Test
    fun `a short slow drag lets the sheet come back, and dismisses nothing`() {
        show(EndPanel())
        val before = compose.onNodeWithTag(EndPanelSheetTag).getUnclippedBoundsInRoot()

        compose.onNodeWithTag(EndPanelSheetTag).performTouchInput {
            swipeDown(startY = top + 10f, endY = top + 40f, durationMillis = 1_000)
        }
        compose.waitForIdle()

        assertThat(asked).isEmpty()
        val after = compose.onNodeWithTag(EndPanelSheetTag).getUnclippedBoundsInRoot()
        assertThat(after.top.value).isWithin(0.5f).of(before.top.value)
    }

    @Test
    fun `a sheet dragged far down then flung back up is not dismissed`() {
        show(EndPanel())

        compose.onNodeWithTag(EndPanelSheetTag).performTouchInput {
            down(Offset(centerX, top + 10f))
            repeat(12) { moveBy(Offset(0f, height * 0.05f), delayMillis = 40) }
            repeat(8) { moveBy(Offset(0f, -height * 0.02f), delayMillis = 8) }
            up()
        }
        compose.waitForIdle()

        assertThat(asked).isEmpty()
    }

    @Test
    fun `close asks to close`() {
        show(EndPanel(rating = 4))

        close().performClick()

        assertThat(asked).containsExactly("close")
    }

    @Test
    fun `the next volume is offered with its title when there is one`() {
        show(EndPanel(nextVolume = next))

        compose.onNodeWithText(text(R.string.reader_end_next_volume)).assertIsDisplayed()
        compose.onNodeWithText("The Next One").assertIsDisplayed()
        view().performClick()

        assertThat(asked).containsExactly("next")
    }

    @Test
    fun `a next volume the server does not name is offered all the same`() {
        show(EndPanel(nextVolume = next.copy(title = null)))

        compose.onNodeWithText(text(R.string.reader_end_next_volume)).assertIsDisplayed()
        view().assertIsEnabled()
    }

    @Test
    fun `without a next volume there is only close`() {
        show(EndPanel())

        compose.onNodeWithText(text(R.string.reader_end_next_volume)).assertDoesNotExist()
        view().assertDoesNotExist()
        close().assertIsEnabled()
    }

    @Test
    fun `while the rating is on its way everything waits, and it shows`() {
        show(EndPanel(rating = 4, nextVolume = next, sending = true))

        (1..5).forEach { star(it).assertIsNotEnabled() }
        view().assertIsNotEnabled()
        close().assertIsNotEnabled()
        assertThat(progress().fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithContentDescription(text(R.string.reader_end_sending)).assertIsDisplayed()

        (1..5).forEach { star(it).performClick() }
        view().performClick()
        close().performClick()

        assertThat(asked).isEmpty()
    }

    @Test
    fun `a rating that could not be saved is said, and the same buttons try again`() {
        show(EndPanel(rating = 4, nextVolume = next, failed = true))

        compose.onNodeWithText(text(R.string.reader_end_rating_failed)).assertIsDisplayed()
        assertThat(progress().fetchSemanticsNodes()).isEmpty()
        star(4).assertIsSelected().assertIsEnabled()

        close().performClick()
        view().performClick()

        assertThat(asked).containsExactly("close", "next").inOrder()
    }

    @Test
    fun `everything that takes a tap is at least 48 dp on each side`() {
        show(EndPanel(rating = 2, nextVolume = next))

        (1..5).forEach { star(it).assertTakesATap() }
        view().performScrollTo().assertTakesATap()
        close().performScrollTo().assertTakesATap()
    }

    private companion object {
        const val MinTouchDp = 48f
    }
}
