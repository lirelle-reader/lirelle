package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A book the car starts, through the session's player to the book loaded and playing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CarBookStartTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }

    /** What the car's rules answer for each book asked. */
    private var request: (BookId) -> LoadRequest? = { LoadRequest.Audiobook(it) }
    private val asked = mutableListOf<BookId>()
    private var failures = 0
    private lateinit var book: BookSession

    private fun TestScope.player(): ChapterPlayer {
        val clock = MonotonicClock { currentTime }
        val timer = SleepTimer(clock)
        val settings = InMemoryPlayerSettingsStore()
        val loader = BookLoader(audio, FakeLibraryRepository(), InMemoryPlaybackStore(), settings) { "Part $it" }
        book = BookSession(
            engine, navigator, loader, SleepDriver(engine, navigator, timer, FakeExactPause()), timer,
            InMemoryPlayerSettingsStore(), { _, _ -> null }, clock, backgroundScope,
        )
        val start = CarBookStart(book, backgroundScope, { asked += it; request(it) }) { failures++ }
        return ChapterPlayer(engine, navigator, book, start)
    }

    private fun TestScope.settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
        }
    }

    @Test
    fun `the book is loaded as the car's rules say, and plays`() = runTest {
        val player = player()

        player.setMediaItem(PlayerSessionContract.startItem(BookId("42"), "The Long Road"))
        shadowOf(Looper.getMainLooper()).idle()
        // Shown as starting at once: the service enters the foreground without the app's screens.
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()
        settle()

        assertThat(asked).containsExactly(BookId("42"))
        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(engine.wantsToPlay).isTrue()
        assertThat(book.extras.value.book?.id).isEqualTo(BookId("42"))
        assertThat(failures).isEqualTo(0)
    }

    @Test
    fun `a finished audiobook asked from its start is loaded from its start`() = runTest {
        request = { LoadRequest.Audiobook(it, fromStart = true) }
        val player = player()

        player.setMediaItem(PlayerSessionContract.startItem(BookId("42"), null))
        settle()

        assertThat(navigator.bookTimeMs()).isEqualTo(0)
        // From its start: the server's place is not asked.
        assertThat(audio.calls).doesNotContain("playbackState 42")
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `a book with nothing to listen to starts nothing, and the failure is said`() = runTest {
        request = { null }
        val player = player()

        player.setMediaItem(PlayerSessionContract.startItem(BookId("42"), null))
        settle()

        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(audio.calls).isEmpty()
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun `a book that cannot be loaded plays nothing, and the failure is said`() = runTest {
        audio.manifestResult = ApiResult.Failure(app.tfdn.lirelle.core.network.ApiError.ServerUnreachable)
        val player = player()

        player.setMediaItem(PlayerSessionContract.startItem(BookId("42"), null))
        settle()

        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun `a second book asked while the first is looked up is the one that plays, whichever is answered first`() =
        runTest {
            // The first book's rules take longer to read than the second's.
            val slow = CompletableDeferred<Unit>()
            player()
            val rules: suspend (BookId) -> LoadRequest? = {
                asked += it
                if (it == BookId("42")) slow.await()
                LoadRequest.Audiobook(it)
            }
            val start = CarBookStart(book, backgroundScope, rules) { failures++ }
            val ended = mutableListOf<String>()

            start.start(BookId("42"), { true }) { ended += "42" }
            runCurrent()
            start.start(BookId("43"), { true }) { ended += "43" }
            settle()
            slow.complete(Unit)
            settle()

            assertThat(asked).containsExactly(BookId("42"), BookId("43")).inOrder()
            assertThat(book.extras.value.book?.id).isEqualTo(BookId("43"))
            // Each start ends once, the one given up too, and that is no failure.
            assertThat(ended).containsExactly("42", "43").inOrder()
            assertThat(failures).isEqualTo(0)
        }

    @Test
    fun `rules that break say the failure too, and the player is not left starting`() = runTest {
        request = { error("A store that cannot be read") }
        val player = player()

        player.setMediaItem(PlayerSessionContract.startItem(BookId("42"), null))
        settle()

        assertThat(player.playbackState).isNotEqualTo(Player.STATE_BUFFERING)
        assertThat(failures).isEqualTo(1)
    }
}
