package app.tfdn.lirelle.feature.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.Shortened
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.OfferedListening
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the player screen shows of its state, and what each control asks for. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()
    private val left = mutableListOf<String>()
    private var state by mutableStateOf(PlayerSamples.playing)

    private fun show(shown: PlayerUiState = PlayerSamples.playing, now: Instant? = null) {
        state = shown
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(
                    state = state,
                    actions = actions,
                    onMinimise = { left += "minimise" },
                    onOpenBook = { left += "book $it" },
                    onOpenSettings = { left += "settings" },
                    now = now,
                )
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun byLabel(label: String) = compose.onNodeWithContentDescription(label)

    @Test
    fun `the top bar minimises the player, and its menu opens the book page and the settings`() {
        show()

        compose.onNodeWithText("Now playing").assertIsDisplayed()
        byLabel("Minimise the player").performClick()
        compose.onNodeWithTag(PlayerMenuTag).performClick()
        compose.onNodeWithText("Book page").performClick()
        compose.onNodeWithTag(PlayerMenuTag).performClick()
        compose.onNodeWithText("Player settings").performClick()

        assertThat(left).containsExactly("minimise", "book 42", "settings").inOrder()
    }

    @Test
    fun `stop listening, from the menu, closes the book and leaves the player`() {
        show()

        compose.onNodeWithTag(PlayerMenuTag).performClick()
        compose.onNodeWithText("Stop listening").performClick()

        assertThat(actions.asked).containsExactly("stopListening")
        assertThat(left).containsExactly("minimise")
    }

    @Test
    fun `without a book, the menu offers no stop`() {
        show(PlayerUiState())

        compose.onNodeWithTag(PlayerMenuTag).performClick()

        compose.onNodeWithText("Player settings").assertIsDisplayed()
        compose.onNodeWithText("Stop listening").assertDoesNotExist()
    }

    @Test
    fun `the title, the credits and the chapter's title`() {
        show()

        compose.onNodeWithText("The Long Road").assertIsDisplayed()
        compose.onNodeWithText("Anne Verlaine · read by Thomas Rivière").assertIsDisplayed()
        compose.onNodeWithText("Middle").assertIsDisplayed()
    }

    @Test
    fun `the credits line in each of its cases`() {
        show()
        val cases = mapOf(
            CreditsLine.AuthorsAndNarrators(Shortened("A", "B", 0), Shortened("N", null, 2)) to
                "A and B · read by N and 2 others",
            CreditsLine.AuthorsOnly(Shortened("A", null, 1)) to "A and 1 other",
            CreditsLine.NarratorsOnly(Shortened("N", "M", 0)) to "Read by N and M",
        )

        cases.forEach { (credits, shown) ->
            state = PlayerSamples.playing.copy(credits = credits)
            compose.onNodeWithText(shown).assertIsDisplayed()
        }
        state = PlayerSamples.playing.copy(credits = CreditsLine.None)
        compose.onNodeWithText("read by", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the credits, the book's line and the skips in French`() {
        show(
            PlayerSamples.playing.copy(
                credits = CreditsLine.AuthorsAndNarrators(Shortened("A", null, 0), Shortened("N", null, 3)),
                chapterIndex = 11,
                chapterCount = 48,
                bookPercent = 37.4,
                bookTimeLeftMs = (9 * 60 + 12) * 60_000L,
            ),
        )

        compose.onNodeWithText("A · lu par N et 3 autres").assertIsDisplayed()
        compose.onNodeWithText("Chapitre 12 sur 48 · 37 % · 9 h 12 restantes").assertIsDisplayed()
        byLabel("Reculer de 15 secondes").assertIsDisplayed()
        byLabel("Avancer de 30 secondes").assertIsDisplayed()
    }

    @Test
    fun `the chapter's bar shows the time played and left`() {
        show()

        compose.onNodeWithText("0:10").assertIsDisplayed()
        compose.onNodeWithText("−0:30").assertIsDisplayed()
        compose.onNodeWithTag(ChapterBarTag).assertIsEnabled()
    }

    @Test
    fun `at 2x the chapter's time left is the listening time`() {
        show(PlayerSamples.playing.copy(speed = 2f))

        compose.onNodeWithText("0:10").assertIsDisplayed()
        compose.onNodeWithText("−0:15").assertIsDisplayed()
    }

    @Test
    fun `dragging the chapter's bar seeks once the finger is lifted`() {
        show()
        val bar = compose.onNodeWithTag(ChapterBarTag)

        bar.performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(width / 10f, 0f)) }
        }
        assertThat(actions.asked).doesNotContain("seekInChapter")
        // The bar and the time follow the finger.
        val range = bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertThat(range.current).isGreaterThan(0.6f)
        compose.onNodeWithText("0:10").assertDoesNotExist()

        bar.performTouchInput { up() }
        assertThat(actions.asked).containsExactly("seekInChapter")
        assertThat(actions.sought.single()).isWithin(0.01f).of(range.current)
    }

    @Test
    fun `a chapter of unknown length shows no time left and cannot be dragged`() {
        show(PlayerSamples.playing.copy(chapterDurationMs = null))

        compose.onNodeWithText("0:10").assertIsDisplayed()
        compose.onNodeWithText("−", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(ChapterBarTag).assertIsNotEnabled()
    }

    @Test
    fun `the book's line, without what is not known`() {
        show(PlayerSamples.playing.copy(bookTimeLeftMs = 9 * 60 * 60_000L + 12 * 60_000L))
        compose.onNodeWithText("Chapter 2 of 3 · 43% · 9 h 12 left").assertIsDisplayed()

        state = PlayerSamples.playing.copy(totalMs = null, bookPercent = null, bookTimeLeftMs = null)
        compose.onNodeWithText("Chapter 2 of 3").assertIsDisplayed()
    }

    @Test
    fun `the five controls say what they do, with the skips' intervals, and ask for it`() {
        show(PlayerSamples.playing.copy(settings = PlayerSettings(backSeconds = 10, forwardSeconds = 45)))

        byLabel("Previous chapter").performClick()
        byLabel("Back 10 seconds").performClick()
        byLabel("Pause").performClick()
        byLabel("Forward 45 seconds").performClick()
        byLabel("Next chapter").performClick()

        assertThat(actions.asked)
            .containsExactly("previousChapter", "back", "playPause", "forward", "nextChapter").inOrder()
    }

    @Test
    fun `the big button plays a paused book`() {
        show(PlayerSamples.playing.copy(playing = false))

        byLabel("Play").assertIsDisplayed()
        byLabel("Pause").assertDoesNotExist()
    }

    @Test
    fun `the bottom row shows the speed and the sleep timer's time left, and opens their sheets`() {
        val sleep = SleepState(SleepChoice.Minutes(30), remainingMs = 22 * 60_000L + 10_000, fading = false)
        show(PlayerSamples.playing.copy(speed = 1.25f, sleep = sleep))

        compose.onNodeWithText("1.25×").assertIsDisplayed()
        compose.onNodeWithText("23 min").assertIsDisplayed()
        compose.onNodeWithTag(SpeedButtonTag).performClick()
        compose.onNodeWithTag(SpeedSheetTag).assertExists()
    }

    @Test
    fun `the sleep timer at the end of the chapter shows the time to it at the speed`() {
        val sleep = SleepState(SleepChoice.EndOfChapter, remainingMs = null, fading = false)
        show(PlayerSamples.playing.copy(speed = 2f, sleep = sleep, chapterElapsedMs = 0, chapterDurationMs = 1_200_000))

        compose.onNodeWithText("10 min").assertIsDisplayed()
    }

    @Test
    fun `the chapters and the sleep timer open their sheets`() {
        show()

        compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()
        compose.onNodeWithTag(ChaptersSheetTag).assertExists()
    }

    @Test
    fun `a newer position is offered with its age, to follow or to ignore`() {
        val now = Instant.parse("2026-11-15T12:00:00Z")
        val offered = OfferedListening(bookTimeMs = (60 * 60 + 23 * 60 + 4) * 1_000L, now - Duration.ofHours(2))
        show(PlayerSamples.playing.copy(newerPosition = offered), now = now)

        compose.onNodeWithText("Go to 1:23:04 (2 hours ago)").assertIsDisplayed()
        compose.onNodeWithText("Go there").performClick()
        compose.onNodeWithText("Ignore").performClick()

        assertThat(actions.asked).containsExactly("acceptNewerPosition", "ignoreNewerPosition").inOrder()
    }

    @Test
    fun `without the moment, a newer position gives its place alone`() {
        show(PlayerSamples.playing.copy(newerPosition = OfferedListening(120_000, updatedAt = null)))

        compose.onNodeWithText("Go to 2:00").assertIsDisplayed()
    }

    @Test
    fun `why playback waits`() {
        show(PlayerSamples.playing.copy(offline = true))
        compose.onNodeWithText(text(R.string.player_offline)).assertIsDisplayed()

        state = PlayerSamples.playing.copy(unreachable = true)
        compose.onNodeWithText(text(R.string.player_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_offline)).assertDoesNotExist()
    }

    @Test
    fun `a first book being read, then one that could not be`() {
        show(PlayerUiState(loading = true))
        compose.onNodeWithTag(PlayerLoadingTag).assertIsDisplayed()

        state = PlayerUiState(loadFailed = true)
        compose.onNodeWithText("This book could not be loaded").assertIsDisplayed()
        compose.onNodeWithTag(PlayerLoadingTag).assertDoesNotExist()
    }

    @Test
    fun `go back is offered after a jump`() {
        show()
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()

        state = PlayerSamples.playing.copy(goBackShown = true)
        compose.onNodeWithTag(GoBackTag).performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("goBack")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the top bar and a minute left in French`() {
        show(PlayerSamples.playing.copy(bookTimeLeftMs = 40_000))

        compose.onNodeWithText("En écoute").assertIsDisplayed()
        compose.onNodeWithText("Chapitre 2 sur 3 · 43 % · 1 min restante").assertIsDisplayed()
    }

    @Test
    fun `a first book that could not be loaded is tried again`() {
        show(PlayerUiState(loadFailed = true, canRetryLoad = true))

        compose.onNodeWithTag(PlayerRetryTag).performClick()

        assertThat(actions.asked).containsExactly("retryLoad")
    }

    @Test
    fun `another book that could not be loaded over the one playing is tried again, or dismissed`() {
        show(PlayerSamples.playing.copy(loadFailed = true, canRetryLoad = true))

        compose.onNodeWithText("This book could not be loaded").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertThat(actions.asked).containsExactly("retryLoad")

        byLabel("Dismiss").performClick()
        compose.onNodeWithText("This book could not be loaded").assertDoesNotExist()
        compose.onNodeWithText("The Long Road").assertIsDisplayed()

        // Another failure later is shown again.
        state = PlayerSamples.playing.copy(loading = true)
        compose.waitForIdle()
        state = PlayerSamples.playing.copy(loadFailed = true)
        compose.onNodeWithText("This book could not be loaded").assertIsDisplayed()
    }

    @Test
    fun `a failure the app does not know how to try again offers no button`() {
        show(PlayerUiState(loadFailed = true))
        compose.onNodeWithText("This book could not be loaded").assertIsDisplayed()
        compose.onNodeWithTag(PlayerRetryTag).assertDoesNotExist()

        state = PlayerSamples.playing.copy(loadFailed = true)
        compose.onNodeWithText("This book could not be loaded").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        byLabel("Dismiss").assertIsDisplayed()
    }

    @Test
    fun `go back takes its room whether shown or not, so the controls do not move`() {
        show()
        val before = byLabel("Pause").fetchSemanticsNode().boundsInRoot

        state = PlayerSamples.playing.copy(goBackShown = true)
        compose.onNodeWithTag(GoBackTag).assertIsDisplayed()

        assertThat(byLabel("Pause").fetchSemanticsNode().boundsInRoot).isEqualTo(before)
    }

    @Test
    fun `while the end panel is open, a screen reader cannot reach the player behind it`() {
        show(PlayerSamples.playing.copy(playing = false, finished = finished(panelSeen = false)))

        compose.onNodeWithText("Book finished").assertIsDisplayed()
        compose.onNodeWithText("Now playing").assertDoesNotExist()
        byLabel("Previous chapter").assertDoesNotExist()
    }

    @Test
    fun `a phone shows no chapters column`() {
        show()

        compose.onNodeWithTag(ChaptersColumnTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window in landscape always shows the chapters, where a tap goes to one`() {
        show()

        compose.onNodeWithTag(ChaptersColumnTag).assertIsDisplayed()
        compose.onNodeWithTag(ChaptersButtonTag).assertDoesNotExist()
        compose.onNodeWithText("Closing").performClick()

        assertThat(actions.asked).containsExactly("goToChapter 2")
    }

    @Test
    fun `a finished book opens its end panel`() {
        show(PlayerSamples.playing.copy(playing = false, finished = finished(panelSeen = false)))

        compose.onNodeWithTag(PanelSheetTag).assertIsDisplayed()
        compose.onNodeWithText("Book finished").assertIsDisplayed()
    }

    @Test
    fun `a finished book whose panel was dismissed offers its rating instead of a play button that does nothing`() {
        show(PlayerSamples.playing.copy(playing = false, finished = finished(panelSeen = true)))

        compose.onNodeWithTag(PanelSheetTag).assertDoesNotExist()
        compose.onNodeWithText("Finished").assertIsDisplayed()
        byLabel("Play").assertDoesNotExist()
        byLabel("Rate the book").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performClick()

        assertThat(actions.asked).containsExactly("openEndPanel")
    }

    private fun finished(panelSeen: Boolean) =
        FinishedState(rating = null, nextVolume = null, sending = false, failed = false, panelSeen = panelSeen)
}
