package app.tfdn.lirelle.feature.comics.screen

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.comics.ComicDownloadTag
import app.tfdn.lirelle.feature.comics.ComicFailure
import app.tfdn.lirelle.feature.comics.ComicOpeningTag
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Received = 3_200_000L
private const val Whole = 9_400_000L

/** A document fetched whole before it opens: how far the fetch is, and what is said of one that cannot be read. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicOpeningDocumentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screen = ScreenUnderTest(ComicReaderUiState(kind = PagedKind.Pdf))

    private fun show(state: ComicReaderUiState) {
        screen.state = state
        compose.setContent { screen.Content() }
        compose.waitForIdle()
    }

    private fun downloadRange(): ProgressBarRangeInfo? = compose.onNodeWithTag(ComicDownloadTag)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `a PDF being fetched shows how far it is, and the sizes`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, opening = OpeningProgress(Received, Whole)))

        compose.onNodeWithTag(ComicDownloadTag).assertIsDisplayed()
            .assertContentDescriptionEquals("Downloading the book…")
        val range = checkNotNull(downloadRange())
        assertThat(range.current).isWithin(0.001f).of(Received.toFloat() / Whole)
        assertThat(range.range).isEqualTo(0f..1f)
        compose.onNodeWithText("${context.fileSize(Received)} of ${context.fileSize(Whole)}").assertIsDisplayed()
        compose.onNodeWithTag(ComicOpeningTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertHasClickAction()
    }

    @Test
    fun `the progress follows the fetch`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, opening = OpeningProgress(Received, Whole)))

        screen.state = screen.state.copy(opening = OpeningProgress(Whole, Whole))
        compose.waitForIdle()

        assertThat(checkNotNull(downloadRange()).current).isEqualTo(1f)
        compose.onNodeWithText("${context.fileSize(Whole)} of ${context.fileSize(Whole)}").assertIsDisplayed()
    }

    @Test
    fun `a fetch whose size is not known shows a mark that does not say how far, and what came`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, opening = OpeningProgress(Received, null)))

        assertThat(downloadRange()).isEqualTo(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithText(context.fileSize(Received)).assertIsDisplayed()
    }

    @Test
    fun `an opening with nothing to fetch whole keeps its plain mark`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf))

        compose.onNodeWithTag(ComicOpeningTag).assertExists()
        compose.onNodeWithTag(ComicDownloadTag).assertDoesNotExist()
    }

    @Test
    fun `a PDF protected by a password says so, and offers no new try`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, phase = ComicPhase.Failed(ComicFailure.Protected)))

        compose.onNodeWithText("This PDF is protected by a password and cannot be opened.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(screen.actions.calls).containsExactly("leave")
    }

    @Test
    fun `a damaged document says so, and can be tried again`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, phase = ComicPhase.Failed(ComicFailure.Damaged)))

        compose.onNodeWithText("This file is damaged and cannot be opened.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertHasClickAction().performClick()

        assertThat(screen.actions.calls).containsExactly("retryOpening")
    }

    @Test
    fun `a damaged copy kept on the device says so, and offers no new try`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, phase = ComicPhase.Failed(ComicFailure.KeptDamaged)))

        compose.onNodeWithText("This file is damaged and cannot be opened.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertHasClickAction()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French`() {
        show(ComicReaderUiState(kind = PagedKind.Pdf, phase = ComicPhase.Failed(ComicFailure.Protected)))
        compose.onNodeWithText("Ce PDF est protégé par un mot de passe et ne peut pas être ouvert.").assertIsDisplayed()

        screen.state = ComicReaderUiState(kind = PagedKind.Pdf, phase = ComicPhase.Failed(ComicFailure.Damaged))
        compose.waitForIdle()
        compose.onNodeWithText("Ce fichier est abîmé et ne peut pas être ouvert.").assertIsDisplayed()

        screen.state = ComicReaderUiState(kind = PagedKind.Pdf, opening = OpeningProgress(Received, Whole))
        compose.waitForIdle()
        compose.onNodeWithText("${context.fileSize(Received)} sur ${context.fileSize(Whole)}").assertIsDisplayed()
    }
}
