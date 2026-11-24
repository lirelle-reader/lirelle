package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode makes the lines under a cover too tall.
class HomePiecesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { LirelleTheme { content() } }
    }

    // The texts of a book are replaced by one description for a screen reader: they are found in the unmerged tree.
    private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    @Test
    fun `a cover row shows its title, its books and opens them`() {
        val opened = mutableListOf<BookId>()
        show {
            CoverRow(
                "Up next",
                listOf(sampleBook("1", "First"), sampleBook("2", "Second")),
                onOpenBook = { opened += it },
            )
        }

        compose.onNodeWithText("Up next").assertIsDisplayed()
        compose.onNode(isHeading()).assertIsDisplayed()
        compose.onNodeWithTag("book-2").performClick()

        assertThat(opened).containsExactly(BookId("2"))
    }

    @Test
    fun `see all is there only when asked for`() {
        var seen = 0
        show {
            Column {
                CoverRow("With", listOf(sampleBook("1")), onOpenBook = {}, onSeeAll = { seen++ })
                CoverRow("Without", listOf(sampleBook("2")), onOpenBook = {})
            }
        }

        compose.onAllNodesWithTag(SeeAllTag).assertCountEquals(1)
        compose.onNodeWithTag(SeeAllTag).performClick()
        assertThat(seen).isEqualTo(1)
    }

    @Test
    fun `see all says which row it belongs to`() {
        show { CoverRow("Up next", listOf(sampleBook("1")), onOpenBook = {}, onSeeAll = {}) }

        compose.onNodeWithContentDescription(context.getString(R.string.row_see_all_description, "Up next"))
            .assertIsDisplayed()
    }

    @Test
    fun `a heading can name its button and say what it does`() {
        var cleared = 0
        show {
            SectionHeading(
                title = "Recent",
                seeAllLabel = "Clear",
                onSeeAll = { cleared++ },
                seeAllDescription = "Clear recent searches",
            )
        }

        compose.onNodeWithText("Clear").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear recent searches").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test
    fun `a series row shows the volume and the series name instead of the author`() {
        val saga = SeriesRef(SeriesId("4"), "A Saga", "2")
        val book = sampleBook("1", "Second volume", author = "The Author", series = saga)
        show { CoverRow("Up next", listOf(book), onOpenBook = {}, showSeries = true) }

        text("A Saga").assertIsDisplayed()
        text("The Author").assertDoesNotExist()
        text("2").assertIsDisplayed()
    }

    @Test
    fun `a row that is not about series shows the author and no volume`() {
        val saga = SeriesRef(SeriesId("4"), "A Saga", "2")
        val book = sampleBook("1", "Second volume", author = "The Author", series = saga)
        show { CoverRow("Recently added", listOf(book), onOpenBook = {}) }

        text("The Author").assertIsDisplayed()
        text("A Saga").assertDoesNotExist()
        text("2").assertDoesNotExist()
    }

    @Test
    fun `a book not loaded yet is a placeholder`() {
        show { CoverRow("Loading", listOf(null, null, null), onOpenBook = {}) }

        compose.onAllNodesWithTag(BookPlaceholderTag).assertCountEquals(3)
    }

    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val done = mutableListOf<String>()

    private fun showCard(book: BookSummary, lastMedium: BookMedium? = null) = show {
        ContinueCard(
            book = book,
            onOpenBook = { done += "page" },
            onRead = { done += "read" },
            onListen = { done += "listen" },
            now = now,
            lastMedium = lastMedium,
        )
    }

    private fun button(medium: BookMedium, id: BookId = BookId("7")) =
        compose.onNodeWithTag(mediumButtonTag(medium, id))

    @Test
    fun `a continue card shows the book, how long ago it was read, and opens its page from the cover`() {
        showCard(readingBook(now.minusSeconds(2 * 3600)))

        text("A Book").assertIsDisplayed()
        text(relativeTime(now.minusSeconds(2 * 3600), now)).assertIsDisplayed()
        compose.onNodeWithTag("continue-open-7").performClick()
        assertThat(done).containsExactly("page")
    }

    @Test
    fun `a book with a text and audio has a button for each, with how far the user is`() {
        showCard(readingBook(now).copy(timeLeftSeconds = 9 * 3600L + 12 * 60))

        text(context.getString(R.string.progress_percent, 11)).assertIsDisplayed()
        text(context.getString(R.string.continue_time_left_hours, 9, 12)).assertIsDisplayed()
        button(BookMedium.Read).performClick()
        button(BookMedium.Listen).performClick()
        assertThat(done).containsExactly("read", "listen").inOrder()
    }

    @Test
    fun `a book with one medium has one button`() {
        showCard(readingBook(now).copy(media = MediaProfile.TextOnly, hasAudioFiles = false))
        button(BookMedium.Read).assertIsDisplayed()
        button(BookMedium.Listen).assertDoesNotExist()
    }

    @Test
    fun `an audiobook has the listening button only`() {
        showCard(readingBook(now).copy(media = MediaProfile.AudioOnly, timeLeftSeconds = 40 * 60L))

        button(BookMedium.Read).assertDoesNotExist()
        text(context.getString(R.string.continue_time_left_minutes, 40)).assertIsDisplayed()
    }

    @Test
    fun `a read-along book without an audio file has a listening button, for its narration, the headphones alone`() {
        showCard(readingBook(now).copy(hasAudioFiles = false))

        button(BookMedium.Read).assertIsDisplayed()
        button(BookMedium.Listen).assertIsDisplayed().performClick()
        textsIn(BookMedium.Listen).assertCountEquals(0)
        assertThat(done).containsExactly("listen")
    }

    @Test
    fun `a listening time not known yet leaves the headphones alone`() {
        showCard(readingBook(now))

        button(BookMedium.Listen).assertIsDisplayed()
        textsIn(BookMedium.Listen).assertCountEquals(0)
        textsIn(BookMedium.Read).assertCountEquals(1)
    }

    private fun textsIn(medium: BookMedium) = compose.onAllNodes(
        hasAnyAncestor(hasTestTag(mediumButtonTag(medium, BookId("7")))) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true,
    )

    @Test
    fun `each button is at least 48 dp high and says what it does`() {
        showCard(readingBook(now).copy(timeLeftSeconds = 9 * 3600L + 12 * 60))

        listOf(BookMedium.Read, BookMedium.Listen).forEach {
            assertThat(button(it).getUnclippedBoundsInRoot().height.value).isAtLeast(48f)
        }
        button(BookMedium.Read)
            .assertContentDescriptionEquals(context.getString(R.string.continue_read_label, "A Book"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        button(BookMedium.Listen)
            .assertContentDescriptionEquals(context.getString(R.string.continue_listen_label, "A Book"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.continue_time_left_description, "9 hours 12 minutes"),
                ),
            )
    }

    @Test
    fun `the cover and the texts of a continue card are read as one item that opens the page`() {
        val at = now.minusSeconds(2 * 3600)
        showCard(readingBook(at))

        val config = compose.onNodeWithTag("continue-open-7").fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(
            listOf("A Book", "Author 7", context.getString(R.string.media_read_along), relativeTime(at, now))
                .joinToString(", "),
        )
        assertThat(config[SemanticsActions.OnClick].label).isEqualTo(context.getString(R.string.book_open_page))
        // Nothing inside is announced on its own.
        compose.onNodeWithText("A Book").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the time left is said in words, one hour and one minute without a plural`() {
        showCard(readingBook(now).copy(timeLeftSeconds = 3600L + 60))

        button(BookMedium.Listen).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "encore 1 heure 1 minute"),
        )
    }

    @Test
    fun `a time left under an hour is said in minutes only`() {
        showCard(readingBook(now).copy(timeLeftSeconds = 40 * 60L))

        button(BookMedium.Listen)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "40 minutes left"))
    }

    @Test
    fun `a continue card without a date shows none`() {
        showCard(readingBook(lastActivityAt = null))

        text("A Book").assertIsDisplayed()
        compose.onAllNodesWithTag(ContinueWhenTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `relative time counts back from the given moment`() {
        val now = Instant.parse("2026-11-13T12:00:00Z")

        assertThat(relativeTime(now.minusSeconds(2 * 3600), now)).contains("2")
        assertThat(relativeTime(now.minusSeconds(2 * 3600), now))
            .isNotEqualTo(relativeTime(now.minusSeconds(5 * 3600), now))
    }

    @Test
    fun `a date a little ahead of the device reads as just now, never in the future`() {
        val now = Instant.parse("2026-11-13T12:00:00Z")

        assertThat(relativeTime(now.plusSeconds(20), now, "Just now")).isEqualTo("Just now")
        assertThat(relativeTime(now.minusSeconds(30), now, "Just now")).isEqualTo("Just now")
        assertThat(relativeTime(now.plusSeconds(20), now)).isEqualTo(relativeTime(now, now))
        assertThat(relativeTime(now.minusSeconds(2 * 3600), now, "Just now")).contains("2")
    }

    @Test
    fun `the card of a book read a moment ago says just now`() {
        showCard(readingBook(lastActivityAt = now.plusSeconds(10)))

        text(context.getString(R.string.relative_just_now)).assertIsDisplayed()
    }

    private fun readingBook(lastActivityAt: Instant?) = sampleBook(
        id = "7",
        title = "A Book",
        progressPercent = 11,
        media = MediaProfile.ReadAlong,
        readState = ReadState.Reading,
    ).copy(lastActivityAt = lastActivityAt, hasAudioFiles = true)
}
