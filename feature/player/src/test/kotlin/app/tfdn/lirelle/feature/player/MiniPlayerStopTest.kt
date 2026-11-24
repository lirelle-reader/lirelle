package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import app.tfdn.lirelle.feature.player.controller.SessionNarrations
import app.tfdn.lirelle.feature.player.controller.SessionSnapshot
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stopping listening from the mini-player: swiped away or by its accessibility action, then "Undo". */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MiniPlayerStopTest {

    @get:Rule
    val compose = createComposeRule()

    private val test = TestScope(UnconfinedTestDispatcher())
    private val harness = PlayerHarness(test)

    /** The books whose reader the bar opened. */
    private val opened = mutableListOf<BookId>()

    @After
    fun tearDown() = test.cancel()

    /** The shell's mini-player and its snackbars, over the book of the session. */
    private fun show(snapshot: SessionSnapshot = SessionBooks.at()) {
        harness.show(snapshot)
        val viewModel = MiniPlayerViewModel(harness.controller, MutableStateFlow(true))
        val snackbars = SnackbarHostState()
        compose.setContent {
            LirelleTheme {
                Column {
                    SnackbarHost(snackbars)
                    MiniPlayer(viewModel, onOpenPlayer = {}, onOpenReader = { opened += it }, snackbars = snackbars)
                }
            }
        }
    }

    private val bar get() = compose.onNodeWithTag(MiniPlayerTag)

    /** Swipes the bar, then waits for it to settle. */
    private fun swipe(gesture: TouchInjectionScope.() -> Unit) {
        bar.performTouchInput(gesture)
        compose.waitForIdle()
    }

    private val playing = PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = true)
    private val paused = PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = false)

    private val closings get() = harness.connection.sent.count { it == PlayerCommand.CloseBook }

    @Test
    fun `swiped to the left past the threshold, the book is closed and a snackbar says so`() {
        show()

        swipe { swipeLeft() }

        assertThat(closings).isEqualTo(1)
        compose.onNodeWithText("Listening stopped").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun `swiped to the right, the same`() {
        show()

        swipe { swipeRight() }

        assertThat(closings).isEqualTo(1)
        compose.onNodeWithText("Listening stopped").assertIsDisplayed()
    }

    @Test
    fun `a short swipe settles back, and nothing is stopped`() {
        show()

        // Slowly: a quick flick goes however short it is.
        swipe { swipeLeft(startX = centerX, endX = centerX - width * ShortSwipe, durationMillis = SlowSwipeMs) }

        assertThat(closings).isEqualTo(0)
        compose.onNodeWithText("Listening stopped").assertDoesNotExist()
        bar.assertIsDisplayed()
    }

    @Test
    fun `the bar goes once nothing is loaded`() {
        show()
        swipe { swipeLeft() }

        // The service closed the book.
        harness.show(SessionSnapshot())

        bar.assertDoesNotExist()
    }

    @Test
    fun `undo loads the book again at its position, playing as it was`() {
        show()
        swipe { swipeLeft() }
        harness.show(SessionSnapshot())

        compose.onNodeWithText("Undo").performClick()

        assertThat(harness.connection.sent.last()).isEqualTo(playing)
    }

    @Test
    fun `undo leaves a book that was paused paused`() {
        show(SessionBooks.at(playing = false))
        swipe { swipeLeft() }
        harness.show(SessionSnapshot())

        compose.onNodeWithText("Undo").performClick()

        assertThat(harness.connection.sent.last()).isEqualTo(paused)
    }

    @Test
    fun `the accessibility action stops listening as the swipe does`() {
        show()

        val stop = bar.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Stop listening" }
        compose.runOnUiThread { stop.action() }
        compose.waitForIdle()

        assertThat(closings).isEqualTo(1)
        compose.onNodeWithText("Listening stopped").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertThat(harness.connection.sent.last()).isEqualTo(playing)
    }

    @Test
    fun `a finished book is swiped away too`() {
        show(SessionBooks.finished())
        compose.onNodeWithText("Finished").assertIsDisplayed()

        swipe { swipeLeft() }

        assertThat(closings).isEqualTo(1)
        compose.onNodeWithText("Listening stopped").assertIsDisplayed()
    }

    @Test
    fun `a narration's bar opens the reader on its book`() {
        show(SessionNarrations.at())

        bar.performClick()

        assertThat(opened).containsExactly(BookId("42"))
    }

    @Test
    fun `a narration swiped away is stopped, and undo narrates it again where it was left, as it was`() {
        show(SessionNarrations.at(playing = false))
        compose.onNodeWithText("Narration · Section 4").assertIsDisplayed()

        swipe { swipeLeft() }
        harness.show(SessionSnapshot())
        compose.onNodeWithText("Undo").performClick()

        assertThat(closings).isEqualTo(1)
        assertThat(harness.connection.sent.last())
            .isEqualTo(PlayerCommand.LoadNarration(BookId("42"), FileId("1"), play = false))
    }

    private companion object {
        /** A tenth of the bar's width: well short of the threshold. */
        const val ShortSwipe = 0.1f
        const val SlowSwipeMs = 1_000L
    }
}
