package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The book page shows only what its source offers, on a phone as on a tablet (spec 2c). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookCapabilitiesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openedAuthors = mutableListOf<AuthorId>()
    private val openedGenres = mutableListOf<String>()

    private val reading = BookReading(42, Instant.parse("2026-03-01T20:00:00Z"), totalSeconds = 7_200, sessionCount = 5)
    private val state = BookUiState(Loadable.Ready(sampleBookDetail()), reading, listening = Loadable.Ready(null))

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(capabilities: SourceCapabilities, width: WindowWidthClass = WindowWidthClass.Compact) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookContent(
                        state = state,
                        onBack = {},
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onOpenAuthor = { openedAuthors += it },
                        onOpenGenre = { openedGenres += it },
                        onRead = { _, _ -> },
                        onListen = { _, _ -> },
                        onNarrate = {},
                        capabilities = capabilities,
                    )
                }
            }
        }
    }

    /** No stars, no time read nor sessions, a plain author name; the progress and the genres stay. */
    private fun assertKomgaLikePage() {
        compose.onAllNodesWithText(text(R.string.fact_rating, "4.5")).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.fact_rating_description, "4.5")).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.reading_time), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.reading_sessions), substring = true).assertCountEquals(0)
        val progress = hasText(text(R.string.reading_progress_value, 42))
        compose.onNode(progress and hasAnyAncestor(hasTestTag("book-reading")))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("2019", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("author-9")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .performClick()
        assertThat(openedAuthors).isEmpty()

        compose.onNodeWithTag("genre-Fantasy").performScrollTo().assertIsDisplayed().performClick()
        assertThat(openedGenres).containsExactly("Fantasy")
    }

    @Test
    fun `on a phone the page of a Komga-like source has no stars, time, sessions nor author link`() {
        show(KomgaLikeCapabilities)

        assertKomgaLikePage()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the page of a Komga-like source has no stars, time, sessions nor author link`() {
        show(KomgaLikeCapabilities, WindowWidthClass.Expanded)

        assertKomgaLikePage()
    }

    @Test
    fun `without genres the page has no genre chips, and keeps its summary`() {
        show(KomgaLikeCapabilities.copy(genres = false))

        compose.onNodeWithText("The summary of the book.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("genre-Fantasy").assertCountEquals(0)
        compose.onAllNodesWithText("Fantasy").assertCountEquals(0)
    }

    private fun showSessionsOnly(capabilities: SourceCapabilities) {
        val sessionsOnly = BookReading(null, lastReadAt = null, totalSeconds = 600, sessionCount = 2)
        val book = sampleBookDetail().copy(readState = null, startedAt = null)
        compose.setContent {
            LirelleTheme {
                BookContent(
                    state = BookUiState(Loadable.Ready(book), sessionsOnly, listening = Loadable.Ready(null)),
                    onBack = {},
                    onRetry = {},
                    onRefresh = {},
                    onOpenBook = {},
                    onOpenAuthor = {},
                    onOpenGenre = {},
                    onRead = { _, _ -> },
                    onListen = { _, _ -> },
                    onNarrate = {},
                    capabilities = capabilities,
                )
            }
        }
    }

    @Test
    fun `a book whose only reading is sessions has no reading block without them`() {
        showSessionsOnly(KomgaLikeCapabilities)

        compose.onAllNodesWithTag("book-reading").assertCountEquals(0)
    }

    @Test
    fun `the same book shows its time read and sessions when the source has them`() {
        showSessionsOnly(SourceCapabilities.Everything)

        compose.onNodeWithTag("book-reading").performScrollTo().assertIsDisplayed()
        val inBlock = hasAnyAncestor(hasTestTag("book-reading"))
        compose.onNode(hasText(text(R.string.reading_time), substring = true) and inBlock).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.reading_sessions), substring = true) and inBlock).assertIsDisplayed()
    }
}
