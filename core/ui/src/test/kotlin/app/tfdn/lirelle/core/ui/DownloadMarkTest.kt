package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mark of a book kept on the device, over the covers of the lists and of the "Continue" cards. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadMarkTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val marks = MutableStateFlow<Map<BookId, DownloadMark>>(
        mapOf(
            BookId("1") to DownloadMark.Kept,
            BookId("2") to DownloadMark.InProgress,
            BookId("3") to DownloadMark.Update,
        ),
    )

    /** Books 1 to 3 carry one mark each; book 4 has none. */
    private val books = (1..4).map { sampleBook("$it", media = MediaProfile.AudioOnly) }

    private fun show(withMarks: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalDownloadMarks provides marks.takeIf { withMarks }) { content() }
            }
        }
    }

    private fun showCollection(presentation: Presentation, withMarks: Boolean = true) = show(withMarks) {
        BookCollection(items = bookItemsOf(books), presentation = presentation, onOpenBook = {})
    }

    private fun showCards(withMarks: Boolean = true) = show(withMarks) {
        Column {
            books.forEach {
                ContinueCard(it.reading(), onOpenBook = {}, onRead = {}, onListen = {}, now = now)
            }
        }
    }

    private fun BookSummary.reading() =
        copy(readState = ReadState.Reading, progressPercent = 11, lastActivityAt = null, hasAudioFiles = true)

    private fun markIn(holder: String, mark: DownloadMark) = compose.onAllNodes(
        hasTestTag(downloadMarkTag(mark)) and hasAnyAncestor(hasTestTag(holder)),
        useUnmergedTree = true,
    )

    /** The holder carries [expected] and no other mark; none at all when it is null. */
    private fun assertMark(holder: String, expected: DownloadMark?) = DownloadMark.entries.forEach {
        markIn(holder, it).assertCountEquals(if (it == expected) 1 else 0)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun spoken(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun assertDisc(mark: DpRect) {
        assertThat((mark.right - mark.left).value).isWithin(0.5f).of(24f)
        assertThat((mark.bottom - mark.top).value).isWithin(0.5f).of(24f)
    }

    private fun assertAtTopStart(mark: DpRect, cover: DpRect) {
        assertDisc(mark)
        assertThat((mark.left - cover.left).value).isWithin(0.5f).of(5f)
        assertThat((mark.top - cover.top).value).isWithin(0.5f).of(5f)
    }

    @Test
    fun `in a grid each kept book carries its mark at the top start of its cover`() {
        showCollection(Presentation.Grid)

        assertMark("book-1", DownloadMark.Kept)
        assertMark("book-2", DownloadMark.InProgress)
        assertMark("book-3", DownloadMark.Update)
        assertMark("book-4", null)
        DownloadMark.entries.forEachIndexed { index, mark ->
            val id = index + 1
            val disc = markIn("book-$id", mark)[0].getUnclippedBoundsInRoot()
            assertAtTopStart(disc, bounds("cover-$id"))
            // The format badge keeps the other corner.
            val badge = compose.onNode(
                hasTestTag(FormatBadgeTag) and hasAnyAncestor(hasTestTag("book-$id")),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            assertThat(badge.left).isGreaterThan(disc.right)
        }
    }

    @Test
    fun `in a list the mark sits at the end of the row, beside the format badge, not over the cover`() {
        showCollection(Presentation.List)

        assertMark("book-1", DownloadMark.Kept)
        assertMark("book-2", DownloadMark.InProgress)
        assertMark("book-3", DownloadMark.Update)
        assertMark("book-4", null)
        val disc = markIn("book-1", DownloadMark.Kept)[0].getUnclippedBoundsInRoot()
        val badge = compose.onNode(
            hasTestTag(FormatBadgeTag) and hasAnyAncestor(hasTestTag("book-1")),
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        assertDisc(disc)
        assertThat(disc.left).isGreaterThan(bounds("cover-1").right)
        assertThat(disc.right).isLessThan(badge.left)
        assertThat(badge.right).isAtMost(bounds("book-1").right)
    }

    @Test
    fun `a continue card carries the mark at the top start of its cover`() {
        showCards()

        assertMark("continue-1", DownloadMark.Kept)
        assertMark("continue-2", DownloadMark.InProgress)
        assertMark("continue-3", DownloadMark.Update)
        assertMark("continue-4", null)
        DownloadMark.entries.forEachIndexed { index, mark ->
            val id = index + 1
            assertAtTopStart(markIn("continue-$id", mark)[0].getUnclippedBoundsInRoot(), bounds("continue-cover-$id"))
        }
    }

    @Test
    fun `nothing is marked where the shell gives no marks`() {
        showCollection(Presentation.Grid, withMarks = false)

        (1..4).forEach { assertMark("book-$it", null) }
        assertThat(spoken("book-1")).isEqualTo("Book 1, Author 1, Audiobook")
    }

    @Test
    fun `no row and no card is marked where the shell gives no marks`() {
        show(withMarks = false) {
            Column {
                BookListRow(books[0], onClick = {})
                ContinueCard(books[0].reading(), onOpenBook = {}, onRead = {}, onListen = {}, now = now)
            }
        }

        assertMark("book-1", null)
        assertMark("continue-1", null)
        assertThat(spoken("book-1")).isEqualTo("Book 1, Author 1, Audiobook")
        assertThat(spoken("continue-open-1")).isEqualTo("Book 1, Author 1, Audiobook")
    }

    @Test
    fun `the mark follows the registry`() {
        showCollection(Presentation.Grid)

        marks.value = mapOf(BookId("4") to DownloadMark.Kept)
        compose.waitForIdle()

        assertMark("book-1", null)
        assertMark("book-4", DownloadMark.Kept)
    }

    @Test
    fun `a grid item says its mark after the rest`() {
        showCollection(Presentation.Grid)

        assertThat(spoken("book-1")).isEqualTo("Book 1, Author 1, Audiobook, downloaded")
        assertThat(spoken("book-2")).isEqualTo("Book 2, Author 2, Audiobook, downloading")
        assertThat(spoken("book-3")).isEqualTo("Book 3, Author 3, Audiobook, update available")
        assertThat(spoken("book-4")).isEqualTo("Book 4, Author 4, Audiobook")
    }

    @Test
    fun `a list row says its mark after the rest`() {
        show {
            val saga = SeriesRef(SeriesId("9"), "A Saga", "3")
            BookListRow(sampleBook("1", series = saga, readState = ReadState.Read), onClick = {})
        }

        assertThat(spoken("book-1")).isEqualTo("Book 1, Author 1, Read, A Saga #3, downloaded")
    }

    @Test
    fun `a continue card says its mark after the rest`() {
        showCards()

        assertThat(spoken("continue-open-1")).isEqualTo("Book 1, Author 1, Audiobook, downloaded")
        assertThat(spoken("continue-open-2")).isEqualTo("Book 2, Author 2, Audiobook, downloading")
        assertThat(spoken("continue-open-3")).isEqualTo("Book 3, Author 3, Audiobook, update available")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the marks are said in French`() {
        showCollection(Presentation.Grid)

        assertThat(spoken("book-1")).endsWith(", téléchargé")
        assertThat(spoken("book-2")).endsWith(", téléchargement en cours")
        assertThat(spoken("book-3")).endsWith(", mise à jour disponible")
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)
    fun `on a narrow phone at the largest text size a marked row keeps its texts, its mark and its badge`() {
        // With a cover, of a scheme nothing is ever fetched for: the tile of a book without one carries its title.
        val long = sampleBook(
            id = "1",
            title = "The Extraordinarily Long Title of a Book Nobody Could Ever Fit on a Single Line",
            author = "Bartholomew Featherstonehaugh-Cholmondeley and Associates",
            media = MediaProfile.ReadAlong,
            series = SeriesRef(SeriesId("9"), "A Saga With a Long Name", "12.5"),
        ).copy(coverUrl = "cover://book/1")
        show { BookListRow(long, onClick = {}) }

        val texts = compose.assertAllTextFits()
        assertThat(texts.map { it.lineCount }).containsExactly(1, 1, 1)
        val row = bounds("book-1")
        val disc = markIn("book-1", DownloadMark.Kept)[0].getUnclippedBoundsInRoot()
        val badge = bounds(FormatBadgeTag)
        assertDisc(disc)
        assertThat(disc.right).isLessThan(badge.left)
        assertThat(badge.right).isAtMost(row.right)
        assertThat(texts.maxOf { it.boundsInRoot.right }).isAtMost(with(compose.density) { disc.left.toPx() })
    }
}
