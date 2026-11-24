package app.tfdn.lirelle.feature.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.Shortened
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.OfferedChapter
import app.tfdn.lirelle.feature.player.controller.OfferedListening
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the player, its sheets or its end panel overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class PlayerTextFitTest(private val screen: String, private val language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val french get() = language == "fr"

    /** The credits line as shown: one line, cut short. */
    private val longCredits
        get() = if (french) {
            "$Author et 3 autres · lu par $Narrator et 2 autres"
        } else {
            "$Author and 3 others · read by $Narrator and 2 others"
        }

    private val now = Instant.parse("2026-11-15T12:00:00Z")

    /** Everything as long as it gets: the title, the credits, the chapter, the book's line, a timer, an offer. */
    private val longest = PlayerUiState(
        bookId = BookId("42"),
        title = LongTitle,
        credits = CreditsLine.AuthorsAndNarrators(Shortened(Author, null, 3), Shortened(Narrator, null, 2)),
        chapterIndex = 11,
        chapterTitle = LongChapter,
        chapterElapsedMs = 59 * 60_000L,
        chapterDurationMs = 2 * 60 * 60_000L,
        chapterCount = 148,
        bookTimeMs = 30 * 60 * 60_000L,
        totalMs = 99 * 60 * 60_000L,
        bookPercent = 37.0,
        bookTimeLeftMs = 69 * 60 * 60_000L + 59 * 60_000L,
        playing = true,
        buffering = true,
        speed = 1.25f,
        sleep = SleepState(SleepChoice.Minutes(45), remainingMs = 44 * 60_000L, fading = false),
        chapters = (0 until 148).map { index ->
            ChapterRow(
                title = if (index % 2 == 0) "$index. $LongChapter" else "Chapter $index",
                // The first row shown when the list opens, two above the current one.
                durationMs = if (index == 9) null else 2 * 60 * 60_000L,
                heard = index < 11,
                current = index == 11,
            )
        },
        // In French, the part and the time in it; in English, the time in the book (the part is "Part 100").
        newerPosition = OfferedListening(
            25 * 60 * 60_000L,
            now.minusSeconds(2 * 60 * 60L),
            OfferedChapter(100, "Partie 100", 59 * 60_000L + 59_000L),
        ),
        offline = true,
        goBackShown = true,
        settings = PlayerSettings(backSeconds = 60, forwardSeconds = 60, lastSleepChoice = SleepChoice.Minutes(45)),
    )

    @Test
    fun `the player with everything long`() {
        val texts = show(longest)

        // The title and the credits keep to one line; the chapter's title takes the lines it needs.
        listOf(LongTitle, longCredits).forEach { text ->
            val found = texts.single { it.text == text }
            assertWithMessage("lines of $found").that(found.lineCount).isEqualTo(1)
            assertWithMessage("$found is cut short").that(found.isEllipsized).isTrue()
        }
        val chapter = texts.first { it.text == LongChapter }
        assertWithMessage("$chapter is cut short").that(chapter.isEllipsized).isFalse()
    }

    @Test
    fun `the player with everything long, at 0,5x`() {
        show(longest.copy(speed = 0.5f))
    }

    @Test
    fun `the player of a finished book, its panel dismissed, the server out of reach`() {
        val finished = FinishedState(rating = 4, nextVolume = null, sending = false, failed = false, panelSeen = true)
        val waiting = longest.copy(offline = false, unreachable = true, newerPosition = null)
        show(waiting.copy(finished = finished, sleep = endOfChapter))
    }

    @Test
    fun `a book that could not be loaded`() {
        show(PlayerUiState(loadFailed = true))
    }

    @Test
    fun `the chapters sheet`() {
        val texts = showSheet(ChaptersButtonTag)

        // The row of unknown length, the first shown, is among the rows judged.
        val unknown = texts.filter { it.text == "Chapter 9" }
        assertWithMessage("the row of unknown length").that(unknown).isNotEmpty()
    }

    @Test
    fun `the player of a book in parts, its line and its tile`() {
        val texts = show(parts)

        val line = texts.filter { it.text.startsWith(text(R.string.player_book_part, 12, 148)) }
        assertWithMessage("the book's line").that(line).isNotEmpty()
        // A wide window shows the parts beside the player: their heading, not a tile.
        assertWithMessage("Parts").that(texts.filter { it.text == text(R.string.player_parts) }).isNotEmpty()
    }

    @Test
    fun `the parts sheet`() {
        val texts = showSheet(ChaptersButtonTag, parts)

        // The heading and the tab beside the bookmarks, then how many there are.
        assertWithMessage("Parts").that(texts.count { it.text == text(R.string.player_parts) }).isAtLeast(2)
        val count = context.resources.getQuantityString(R.plurals.player_parts_count, 148, 148)
        assertWithMessage("the count").that(texts.filter { it.text.startsWith(count) }).isNotEmpty()
        assertWithMessage("a row").that(texts.filter { it.text == text(R.string.player_part, 10) }).isNotEmpty()
    }

    @Test
    fun `the speed sheet`() {
        showSheet(SpeedButtonTag)
    }

    @Test
    fun `the sleep timer sheet, running`() {
        showSheet(SleepButtonTag)
    }

    @Test
    fun `the sleep timer sheet, at the end of the chapter`() {
        showSheet(SleepButtonTag, longest.copy(sleep = endOfChapter))
    }

    @Test
    fun `the end panel with everything in it, after a failure`() {
        val finished = FinishedState(3, nextVolume = next, sending = false, failed = true, panelSeen = false, kept)
        val texts = show(longest.copy(finished = finished, listenedSeconds = 99 * 60 * 60L))

        val volume = texts.single { it.text == LongNextTitle }
        assertWithMessage("lines of $volume").that(volume.lineCount).isEqualTo(1)
    }

    @Test
    fun `the end panel while the rating is on its way`() {
        val finished = FinishedState(5, nextVolume = next, sending = true, failed = false, panelSeen = false, kept)
        show(longest.copy(finished = finished))
    }

    @Test
    fun `the end panel of a book that goes from the device when it closes`() {
        val going = kept.copy(deleting = true)
        val finished = FinishedState(null, nextVolume = next, sending = false, failed = false, panelSeen = false, going)
        show(longest.copy(finished = finished))
    }

    @Test
    fun `the end panel of a book whose text is read by pages`() {
        val document = kept.copy(textName = TextName.Djvu)
        val finished = FinishedState(5, nextVolume = next, sending = false, failed = false, panelSeen = false, document)
        show(longest.copy(finished = finished))
    }

    /** The same book in parts, as a book of files named alike is shown. */
    private val parts
        get() = longest.copy(
            inParts = true,
            chapterTitle = text(R.string.player_part, 12),
            chapters = longest.chapters.mapIndexed { index, row ->
                row.copy(title = text(R.string.player_part, index + 1))
            },
        )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Every format, the longest phrase, and a size of four digits. */
    private val kept = DeviceCopy(DownloadFormat.entries, bytes = 1_234_567_890_123, deleting = false)

    private val endOfChapter = SleepState(SleepChoice.EndOfChapter, remainingMs = null, fading = false)
    private val next = NextVolume(bookId = BookId("43"), title = LongNextTitle, seriesIndex = "12.5")

    private fun show(state: PlayerUiState): List<LaidOutText> {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(state, RecordingActions(), {}, {}, {}, now = now)
            }
        }
        // The title, the credits and the next volume's title are the texts meant to be cut: one line, then an ellipsis.
        return compose.assertAllTextFits(unbreakable = setOf(LongTitle, longCredits, LongNextTitle))
    }

    private fun showSheet(tag: String, state: PlayerUiState = longest): List<LaidOutText> {
        val shown = show(state)
        // A wide window shows the chapters beside the player, without a button.
        if (tag == ChaptersButtonTag && screen == Wide) return shown
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        return compose.assertAllTextFits(unbreakable = setOf(LongTitle, longCredits, LongNextTitle))
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"

        /** A phone turned: the cover beside the player, the chapters in their sheet. */
        private const val Turned = "w891dp-h411dp"
        private const val LongTitle =
            "A title long enough to need several lines on a narrow phone at a large text size, and then some more"
        private const val Author = "Marguerite-Hortense de La Villemarqué"
        private const val Narrator = "Jean-Baptiste Delacroix"
        private const val LongChapter =
            "The chapter whose title is a whole sentence about the crossing of the strait at night in the storm"
        private const val LongNextTitle =
            "The following volume, whose title goes on and on well past the width of any sheet, on any screen, " +
                "because its publisher could not choose between a title and a summary"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide, Turned).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
