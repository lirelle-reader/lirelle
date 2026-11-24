package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.testing.laidOutTexts
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookCoverTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(shape: CoverShape) {
        compose.setContent {
            LirelleTheme {
                BookCover(
                    url = null,
                    title = "A title",
                    shape = shape,
                    modifier = Modifier.width(120.dp).testTag("cover"),
                )
            }
        }
    }

    @Test
    fun `a portrait cover is two thirds as wide as it is tall`() {
        show(CoverShape.Portrait)

        val bounds = compose.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isWithin(1f).of(180f)
    }

    @Test
    fun `a square cover is square`() {
        show(CoverShape.Square)

        val bounds = compose.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isWithin(1f).of(120f)
    }

    @Test
    fun `a book without a cover shows its title instead`() {
        show(CoverShape.Portrait)

        compose.onNodeWithText("A title", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a book without a cover can have a plain tile, of the same shape`() {
        compose.setContent {
            LirelleTheme {
                BookCover(
                    url = null,
                    title = "A title",
                    shape = CoverShape.Portrait,
                    modifier = Modifier.width(120.dp).testTag("cover"),
                    showTitleWhenMissing = false,
                )
            }
        }

        assertThat(compose.laidOutTexts()).isEmpty()
        val bounds = compose.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isWithin(1f).of(180f)
    }

    @Test
    fun `the placeholder of a book holds no text`() {
        compose.setContent { LirelleTheme { BookPlaceholder(Modifier.width(120.dp)) } }

        assertThat(compose.laidOutTexts()).isEmpty()
    }
}
