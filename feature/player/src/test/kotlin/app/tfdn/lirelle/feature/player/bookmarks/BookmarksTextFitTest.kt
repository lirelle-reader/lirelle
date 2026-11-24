package app.tfdn.lirelle.feature.player.bookmarks

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.player.ChaptersButtonTag
import app.tfdn.lirelle.feature.player.SleepButtonTag
import app.tfdn.lirelle.feature.player.SpeedButtonTag
import app.tfdn.lirelle.feature.player.PlayerContent
import app.tfdn.lirelle.feature.player.PlayerSamples
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.RecordingActions
import app.tfdn.lirelle.feature.player.bookmarks.FakeAudioBookmarks.Companion.bookmark
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the bookmark tile, the bookmarks tab, the edit dialog or a snackbar overflows or is clipped. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class BookmarksTextFitTest(private val screen: String, language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** A long chapter, holding bookmarks with long titles and notes, many of them. */
    private val longest = PlayerSamples.playing.copy(
        chapterTitle = LongChapter,
        chapters = listOf(
            ChapterRow("Opening", 60_000, heard = true, current = false, startMs = 0),
            ChapterRow(LongChapter, 2 * 60 * 60_000L, heard = false, current = true, startMs = 60_000),
        ),
        bookmarks = listOf(
            bookmark("b1", 61 * 60_000L + 59_000, LongTitle, note = LongNote),
            bookmark("b2", 90_000, "The ferry", note = "Wind at the bow"),
        ) + (3..12).map { bookmark("b$it", 100_000L + it * 1_000) },
    )

    private fun show(state: PlayerUiState = longest) {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(state, actions, {}, {}, {})
            }
        }
    }

    /** The bookmark's title and note, and the player's credits, are the texts meant to be cut short. */
    private fun judge(): List<LaidOutText> =
        compose.assertAllTextFits(
            unbreakable = setOf(LongTitle, LongNote, text(R.string.player_credits_both, Author, Narrator)),
        )

    /** The labels of buttons, tiles and tabs: one line, and whole. */
    private fun List<LaidOutText>.assertWhole(vararg labels: String) = labels.forEach { label ->
        val shown = filter { it.text == label }
        assertWithMessage("$label on screen").that(shown).isNotEmpty()
        shown.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    /** The bookmarks tab: in the chapters sheet, or in the column always shown beside the player. */
    private fun openBookmarksTab(count: Int) {
        if (screen != Wide) compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()
        compose.onNode(
            hasText(text(R.string.player_bookmarks_tab, count)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
        ).performClick()
    }

    @Test
    fun `the bottom row with the bookmark tile`() {
        show()

        val tiles = mutableListOf(R.string.player_speed, R.string.player_sleep, R.string.player_bookmark)
        if (screen != Wide) tiles += R.string.player_chapters
        judge().assertWhole(*tiles.map(::text).toTypedArray())

        // Lines as evenly filled as can be (two and two, never three and one), each tile as wide as its neighbours.
        val tags = listOf(SpeedButtonTag, SleepButtonTag, ChaptersButtonTag, BookmarkButtonTag)
        val bounds = tags.mapNotNull { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        }
        val lines = bounds.groupBy { it.top.roundToInt() }.values
        assertWithMessage("tiles on each line").that(lines.maxOf { it.size } - lines.minOf { it.size }).isAtMost(1)
        lines.forEach { line ->
            assertWithMessage("widths on a line").that(line.maxOf { it.width } - line.minOf { it.width }).isAtMost(2f)
        }
    }

    @Test
    fun `the bookmarks tab, a long title, a long note, a long chapter`() {
        show()
        openBookmarksTab(longest.bookmarks.size)

        val texts = judge()
        val tabs = listOf(text(R.string.player_chapters), text(R.string.player_bookmarks_tab, longest.bookmarks.size))
        texts.assertWhole(*tabs.toTypedArray())
        // In sight too: a tab goes past the edge of the window only where the two cannot fit side by side.
        val window = compose.onAllNodes(isRoot())[0].getUnclippedBoundsInRoot()
        if (screen != Narrow || fontScale == 1f) {
            tabs.forEach {
                val labels = compose.onAllNodesWithText(it)
                repeat(labels.fetchSemanticsNodes().size) { index ->
                    val right = labels[index].getUnclippedBoundsInRoot().right
                    assertWithMessage("$it within the window").that(right).isAtMost(window.right)
                }
            }
        }
        val title = texts.single { it.text == LongTitle }
        assertWithMessage("lines of $title").that(title.lineCount).isEqualTo(1)
        val note = texts.single { it.text == LongNote }
        assertWithMessage("lines of $note").that(note.lineCount).isEqualTo(2)
    }

    @Test
    fun `the empty bookmarks tab`() {
        show(longest.copy(bookmarks = emptyList()))
        openBookmarksTab(0)

        judge()
    }

    @Test
    fun `the edit dialog, a long title, a long note`() {
        show()
        openBookmarksTab(longest.bookmarks.size)
        editLongest()

        judge().assertWhole(text(R.string.player_bookmark_cancel), text(R.string.player_bookmark_save))
    }

    @Test
    fun `the edit dialog, the title wiped out`() {
        show()
        openBookmarksTab(longest.bookmarks.size)
        editLongest()
        compose.onNodeWithTag(BookmarkTitleFieldTag).performTextClearance()

        val texts = judge()
        texts.assertWhole(text(R.string.player_bookmark_cancel), text(R.string.player_bookmark_save))
        assertWithMessage("the title is needed").that(texts.map { it.text })
            .contains(text(R.string.player_bookmark_title_needed))
    }

    @Test
    fun `the snackbar of a bookmark deleted, over the tab`() {
        show()
        openBookmarksTab(longest.bookmarks.size)
        onLongest(R.string.player_bookmark_delete)

        val texts = judge()
        texts.assertWhole(text(R.string.player_bookmark_undo))
        assertWithMessage("the snackbar").that(texts.map { it.text }).contains(text(R.string.player_bookmark_deleted))
    }

    /** The dialog on the bookmark of the long title, by the row's action. */
    private fun editLongest() = onLongest(R.string.player_bookmark_edit)

    /** The row's action [label] on the bookmark of the long title. */
    private fun onLongest(label: Int) {
        val row = compose.onNode(hasText(LongTitle) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == text(label) }
        compose.runOnIdle { action.action() }
    }

    @Test
    fun `the snackbar of a bookmark added`() {
        actions.addAnswer = AddOutcome.Added(longest.bookmarks.first())
        show()
        compose.onNodeWithTag(BookmarkButtonTag).performScrollTo().performClick()

        judge().assertWhole(text(R.string.player_bookmark_edit))
    }

    companion object {
        private const val Author = "Anne Verlaine"
        private const val Narrator = "Thomas Rivière"
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val LongTitle =
            "A title long enough to be cut short on any screen, about the crossing of the strait at night in the storm"
        private const val LongChapter =
            "The chapter whose title is a whole sentence about the crossing of the strait at night in the storm"
        private const val LongNote =
            "A note that goes on: the price of coffee in 1961, the letter from the grandfather, the passage on " +
                "joined-up writing to read again with Jeanne, and why the ferry's wind matters to the whole book."

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
