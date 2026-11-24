package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.download.DownloadTileTag
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the reading actions and the download tile of the book page are laid out, by width and text size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
@Config(qualifiers = Phone)
class BookActionsLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val reading = BookReading(62, lastReadAt = null, totalSeconds = 0, sessionCount = 0)
    private val listening = PlaybackState("aud_1", 60_000, 12.0, false, null, version = "v1", contentVersion = null)

    /** A book read and listened to: "Resume" and a percentage under each button. */
    private val both = BookUiState(Loadable.Ready(sampleBookDetail()), reading, listening = Loadable.Ready(listening))

    private fun show(state: BookUiState = both, width: WindowWidthClass = WindowWidthClass.Compact) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookContent(
                        state = state,
                        onBack = {},
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onOpenAuthor = {},
                        onOpenGenre = {},
                        onRead = { _, _ -> },
                        onListen = { _, _ -> },
                        onNarrate = {},
                    )
                }
            }
        }
    }

    private fun bounds(tag: String): DpRect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun disc(button: String): DpRect = compose
        .onNode(hasTestTag(ActionRingDiscTag) and hasAnyAncestor(hasTestTag(button)), useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    private fun centre(rect: DpRect): Float = (rect.left + rect.right).value / 2

    /** Read then Listen on one line, each in the middle of its half of the block, its texts under its disc. */
    private fun assertSideBySide() {
        val block = bounds("book-actions")
        val read = bounds(MainActionTag)
        val listen = bounds(ListenActionTag)
        assertThat(listen.top).isEqualTo(read.top)
        assertThat(listen.left).isAtLeast(read.right)
        assertThat(read.left).isAtLeast(block.left)
        assertThat(listen.right).isAtMost(block.right)
        // Two equal columns, a gap of 8 dp between them.
        val column = (block.width.value - 8) / 2
        assertThat(centre(disc(MainActionTag))).isWithin(1f).of(block.left.value + column / 2)
        assertThat(centre(disc(ListenActionTag))).isWithin(1f).of(block.right.value - column / 2)
        assertThat(disc(ListenActionTag).top).isEqualTo(disc(MainActionTag).top)
        assertTextsUnderDisc(MainActionTag)
        assertTextsUnderDisc(ListenActionTag)
        assertWordsWhole()
    }

    private fun textsOf(button: String) = bounds(button).let { area ->
        compose.laidOutTexts().filter { text ->
            val at = text.boundsInRoot
            text.isLabel && at.left >= area.left.value && at.right <= area.right.value &&
                at.top >= area.top.value && at.bottom <= area.bottom.value
        }
    }

    private fun assertTextsUnderDisc(button: String) {
        val texts = textsOf(button)
        assertThat(texts).isNotEmpty()
        texts.forEach { assertWithMessage("top of $it").that(it.boundsInRoot.top).isAtLeast(disc(button).bottom.value) }
    }

    private fun assertTextsBesideDisc(button: String) {
        val texts = textsOf(button)
        assertThat(texts).isNotEmpty()
        texts.forEach {
            assertWithMessage("left of $it").that(it.boundsInRoot.left).isAtLeast(disc(button).right.value)
        }
    }

    /** No word of a button is cut short. */
    private fun assertWordsWhole() = (textsOf(MainActionTag) + textsOf(ListenActionTag)).forEach {
        assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse()
    }

    /** One above the other, each with its texts beside its disc, the discs in one column. */
    private fun assertStacked() {
        val block = bounds("book-actions")
        val read = bounds(MainActionTag)
        val listen = bounds(ListenActionTag)
        assertThat(listen.top).isAtLeast(read.bottom)
        assertThat(disc(ListenActionTag).left).isEqualTo(disc(MainActionTag).left)
        assertThat(read.left).isAtLeast(block.left)
        assertThat(maxOf(read.right, listen.right)).isAtMost(block.right)
        assertTextsBesideDisc(MainActionTag)
        assertTextsBesideDisc(ListenActionTag)
        assertWordsWhole()
    }

    @Test
    fun `on a phone the two reading actions are side by side`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = "fr-$Phone")
    fun `on a phone the two reading actions are side by side in French`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = Narrow)
    fun `on a narrow phone the two reading actions are side by side`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = "fr-$Narrow")
    fun `on a narrow phone the two reading actions are side by side in French`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = Wide)
    fun `on a wide screen the two reading actions are side by side, in the left column under the cover`() {
        show(width = WindowWidthClass.Expanded)

        assertSideBySide()
        val block = bounds("book-actions")
        assertThat(block.top).isAtLeast(bounds(BookCoverTag).bottom)
        assertThat(block.left).isEqualTo(bounds("book-header").left)
        assertThat(block.right).isAtMost(bounds("book-about").left)
    }

    @Test
    @Config(qualifiers = "fr-$Wide")
    fun `on a wide screen the two reading actions are side by side in French`() {
        show(width = WindowWidthClass.Expanded)

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = Narrow, fontScale = 2f)
    fun `on a narrow phone with very large text the reading actions stay side by side while their words fit`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = "fr-$Narrow", fontScale = 2f)
    fun `on a narrow phone with very large text the reading actions stay side by side in French`() {
        show()

        assertSideBySide()
    }

    @Test
    @Config(qualifiers = "fr-$Narrow", fontScale = 2f)
    fun `on a narrow phone with very large text the French Start is wider than half the line, the actions stack`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail()), listening = Loadable.Ready(null)))

        assertStacked()
    }

    @Test
    @Config(qualifiers = Narrow, fontScale = 2f)
    fun `on a narrow phone with very large text the English Start fits, the actions stay side by side`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail()), listening = Loadable.Ready(null)))

        assertSideBySide()
    }

    @Test
    @Config(fontScale = 2f)
    fun `where a word cannot be whole in half the width, the reading actions stack`() {
        val starts = BookStarts({ _, _ -> }, { _, _ -> }, {})
        compose.setContent {
            LirelleTheme {
                // Too narrow for "Resume ·" in half of it, wide enough for it beside a disc.
                Box(Modifier.width(200.dp)) {
                    ActionsBlock(sampleBookDetail(), reading, Loadable.Ready(listening), starts)
                }
            }
        }

        assertStacked()
    }

    @Test
    fun `the download tile is in the middle of its own line, under the reading actions`() = assertTileUnderActions()

    @Test
    @Config(qualifiers = "fr-$Narrow", fontScale = 2f)
    fun `the download tile is under the reading actions on a narrow phone with very large text`() =
        assertTileUnderActions()

    private fun assertTileUnderActions() {
        show()

        val block = bounds("book-actions")
        val tile = bounds(DownloadTileTag)
        assertThat(tile.top).isAtLeast(bounds(MainActionTag).bottom)
        assertThat(tile.top).isAtLeast(bounds(ListenActionTag).bottom)
        assertThat(centre(tile)).isWithin(1f).of(centre(block))
        assertThat(tile.left).isAtLeast(block.left)
        assertThat(tile.right).isAtMost(block.right)
    }

    @Test
    fun `a single reading action is in the middle of the line, its texts beside its disc`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.TextOnly)), reading))

        val block = bounds("book-actions")
        val read = bounds(MainActionTag)
        assertThat(centre(read)).isWithin(1f).of(centre(block))
        assertTextsBesideDisc(MainActionTag)
        textsOf(MainActionTag).forEach { assertThat(it.isEllipsized).isFalse() }
    }
}

private const val Phone = "w411dp-h891dp"
private const val Narrow = "w320dp-h640dp"
private const val Wide = "w1280dp-h800dp"
