package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BookSessionTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }
    private val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(sampleBookDetail(id = "42")) }
    private val settings = InMemoryPlayerSettingsStore(PlayerSettings(backSeconds = 10, forwardSeconds = 20))
    private val exactPause = FakeExactPause()
    private val downloads = FakeDownloads()
    private val left = mutableListOf<Long?>()
    private lateinit var timer: SleepTimer

    /** The finished books left for another one. */
    private val finishedLeft = mutableListOf<BookId>()

    private fun TestScope.session(): BookSession {
        val clock = MonotonicClock { currentTime }
        timer = SleepTimer(clock)
        val loader = BookLoader(
            audio, library, InMemoryPlaybackStore(), settings,
            kept = KeptBooks(downloads, FakeKeptBookData(), BookHolds.None),
        ) { "Part $it" }
        val sleep = SleepDriver(engine, navigator, timer, exactPause)
        return BookSession(
            engine, navigator, loader, sleep, timer, settings,
            { series, book -> (audio.nextAudioVolume(series, book) as? ApiResult.Success)?.value },
            clock, backgroundScope,
        ).also {
            it.leaving = { left += navigator.bookTimeMs() }
            it.finishedLeft = { id -> finishedLeft += id }
            runCurrent()
        }
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun TestScope.loaded(): BookSession = session().also {
        audio.playbackStateResult = ApiResult.Success(null)
        it.handle(PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = true))
        settle()
    }

    @Test
    fun `loading a book gives the engine its files at its place and speed, and plays`() = runTest {
        settings.speeds[BookId("42")] = 1.5f
        val session = loaded()

        assertThat(engine.items).hasSize(2)
        assertThat(engine.speed).isEqualTo(1.5f)
        assertThat(engine.wantsToPlay).isTrue()
        val book = session.extras.value.book!!
        assertThat(book.id).isEqualTo(BookId("42"))
        assertThat(book.title).isEqualTo("The Long Road")
        assertThat(book.narrators).containsExactly("Thomas Rivière")
        assertThat(book.totalMs).isEqualTo(160_000)
        assertThat(session.extras.value.loading).isFalse()
    }

    @Test
    fun `the session says a book whose chapters have no titles is in parts, another is in chapters`() = runTest {
        val titled = Manifests.twoFiles()
        val untitled = titled.copy(bookId = BookId("43"), chapters = titled.chapters.map { it.copy(title = "") })
        audio.manifests = mapOf(BookId("43") to ApiResult.Success(untitled))
        val session = loaded()
        assertThat(session.extras.value.book!!.inParts).isFalse()

        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()

        assertThat(session.extras.value.book!!.inParts).isTrue()
    }

    @Test
    fun `the session knows whether the book loaded plays from its download, book after book`() = runTest {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        // Another book is another manifest: the session tells them apart by it.
        val other = Manifests.twoFiles().copy(bookId = BookId("43"))
        audio.manifests = mapOf(BookId("43") to ApiResult.Success(other))
        val session = loaded()
        assertThat(session.playsDownload).isTrue()

        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()
        assertThat(session.playsDownload).isFalse()

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        assertThat(session.playsDownload).isTrue()

        session.handle(PlayerCommand.CloseBook)
        settle()
        assertThat(session.playsDownload).isFalse()
    }

    @Test
    fun `a book that cannot be read says so and the one playing stays`() = runTest {
        val session = loaded()
        audio.manifestResult = ApiResult.Failure(ApiError.ServerUnreachable)

        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()

        assertThat(session.extras.value.loadFailed).isTrue()
        assertThat(session.extras.value.book!!.id).isEqualTo(BookId("42"))
        assertThat(left).isEmpty()
    }

    @Test
    fun `another book leaves the current one first`() = runTest {
        val session = loaded()
        engine.positionMs = 33_000
        engine.update()

        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()

        assertThat(left).containsExactly(33_000L)
    }

    @Test
    fun `the book held, paused, plays on where it is without being loaded again`() = runTest {
        val session = loaded()
        engine.wantsToPlay = false
        engine.positionMs = 33_000
        engine.update()
        val calls = audio.calls.size
        val seeks = engine.seeks.size
        val prepares = engine.prepares

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(engine.positionMs).isEqualTo(33_000)
        assertThat(engine.seeks).hasSize(seeks)
        assertThat(engine.prepares).isEqualTo(prepares)
        assertThat(audio.calls).hasSize(calls)
        assertThat(session.extras.value.loading).isFalse()
    }

    @Test
    fun `the book held and playing is left as it is`() = runTest {
        val session = loaded()
        engine.positionMs = 33_000
        engine.update()
        val calls = audio.calls.size
        val seeks = engine.seeks.size

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(engine.positionMs).isEqualTo(33_000)
        assertThat(engine.seeks).hasSize(seeks)
        assertThat(audio.calls).hasSize(calls)
    }

    @Test
    fun `the book held is loaded again when its player failed, or when it ended`() = runTest {
        val session = loaded()
        engine.failure = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        engine.update()
        val calls = audio.calls.count { it == "manifest 42" }

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        assertThat(audio.calls.count { it == "manifest 42" }).isEqualTo(calls + 1)

        engine.engineState = Player.STATE_ENDED
        engine.update()
        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        assertThat(audio.calls.count { it == "manifest 42" }).isEqualTo(calls + 2)
    }

    @Test
    fun `the book held is loaded again after another failed to load, and the failure goes`() = runTest {
        val session = loaded()
        val manifest = audio.manifestResult
        audio.manifestResult = ApiResult.Failure(ApiError.ServerUnreachable)
        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()
        assertThat(session.extras.value.loadFailed).isTrue()
        audio.manifestResult = manifest
        val calls = audio.calls.count { it == "manifest 42" }

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()

        assertThat(audio.calls.count { it == "manifest 42" }).isEqualTo(calls + 1)
        assertThat(session.extras.value.loadFailed).isFalse()
        assertThat(session.extras.value.book!!.id).isEqualTo(BookId("42"))
    }

    @Test
    fun `a failed reload of the book's changed files is tried again by loading it`() = runTest {
        val session = loaded()
        val manifest = audio.manifestResult
        audio.manifestResult = ApiResult.Failure(ApiError.ServerUnreachable)
        // The files changed on the server: the same book again, at its place.
        session.load(BookId("42"), fromStart = false, play = true, at = 30_000)
        settle()
        assertThat(session.extras.value.loadFailed).isTrue()
        audio.manifestResult = manifest
        val calls = audio.calls.count { it == "manifest 42" }

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()

        assertThat(audio.calls.count { it == "manifest 42" }).isEqualTo(calls + 1)
        assertThat(session.extras.value.loadFailed).isFalse()
    }

    @Test
    fun `the book held is loaded again from its start when listened to again`() = runTest {
        val session = loaded()
        engine.positionMs = 33_000
        engine.update()
        val calls = audio.calls.count { it == "manifest 42" }

        session.handle(PlayerCommand.LoadBook(BookId("42"), fromStart = true))
        settle()

        assertThat(audio.calls.count { it == "manifest 42" }).isEqualTo(calls + 1)
        assertThat(navigator.bookTimeMs()).isEqualTo(0)
    }

    @Test
    fun `back and forward use the intervals of the settings`() = runTest {
        val session = loaded()
        session.handle(PlayerCommand.SeekBookTime(50_000))
        settle()

        session.handle(PlayerCommand.Forward)
        assertThat(navigator.bookTimeMs()).isEqualTo(70_000)
        session.handle(PlayerCommand.Back)
        assertThat(navigator.bookTimeMs()).isEqualTo(60_000)
    }

    @Test
    fun `play after a pause starts a little before, the first play does not`() = runTest {
        val session = loaded()
        session.handle(PlayerCommand.SeekBookTime(50_000))
        session.play()
        assertThat(navigator.bookTimeMs()).isEqualTo(50_000)

        session.onPaused()
        advanceTimeBy(60_000)
        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(48_000)
    }

    @Test
    fun `play after the sleep timer stopped starts from before the fade`() = runTest {
        val session = loaded()
        session.handle(PlayerCommand.SeekBookTime(70_000))
        session.handle(PlayerCommand.StartSleep(SleepChoice.EndOfChapter))
        settle()
        assertThat(settings.settings.value.lastSleepChoice).isEqualTo(SleepChoice.EndOfChapter)
        exactPause.reach()
        session.onPaused()

        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(95_000)
    }

    @Test
    fun `the speed is set and kept for the book`() = runTest {
        val session = loaded()

        session.handle(PlayerCommand.SetSpeed(1.27f))
        settle()

        assertThat(engine.speed).isEqualTo(1.25f)
        assertThat(settings.speeds[BookId("42")]).isEqualTo(1.25f)
    }

    @Test
    fun `a finished book finds its next volume, which listen-to-next plays`() = runTest {
        audio.nextVolumeResult = ApiResult.Success(NextVolume(BookId("43"), "The Next Road", "2"))
        val session = loaded()

        session.onFinished()
        settle()

        assertThat(audio.calls).contains("nextAudioVolume 4 42")
        assertThat(session.extras.value.finished)
            .isEqualTo(FinishedBook(NextVolume(BookId("43"), "The Next Road", "2")))

        session.handle(PlayerCommand.ListenToNext)
        settle()
        assertThat(audio.calls).contains("manifest 43")
        assertThat(session.extras.value.finished).isNull()
        // Its listening is over: the next one of book 42 captures its own baseline.
        assertThat(finishedLeft).containsExactly(BookId("42"))
    }

    @Test
    fun `a finished book listened to again from its start begins a new listening`() = runTest {
        val session = loaded()
        session.onFinished()
        settle()

        session.handle(PlayerCommand.LoadBook(BookId("42"), fromStart = true))
        settle()

        assertThat(finishedLeft).containsExactly(BookId("42"))
    }

    @Test
    fun `a finished book closed ends its listening, one closed before its end does not`() = runTest {
        val session = loaded()
        session.handle(PlayerCommand.CloseBook)
        settle()
        assertThat(finishedLeft).isEmpty()

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        session.onFinished()
        settle()
        session.handle(PlayerCommand.CloseBook)
        settle()

        assertThat(finishedLeft).containsExactly(BookId("42"))
    }

    @Test
    fun `a book left before its end is not a finished one`() = runTest {
        val session = loaded()

        session.handle(PlayerCommand.LoadBook(BookId("43")))
        settle()

        assertThat(finishedLeft).isEmpty()
    }

    @Test
    fun `closing forgets the book`() = runTest {
        val session = loaded()

        session.handle(PlayerCommand.CloseBook)
        settle()

        assertThat(engine.items).isEmpty()
        assertThat(navigator.playlist).isNull()
        assertThat(session.extras.value).isEqualTo(PlayerSessionExtras())
        assertThat(left).hasSize(1)
    }

    @Test
    fun `after the sleep timer stopped, picking a chapter plays from that chapter`() = runTest {
        val session = loaded()
        session.handle(PlayerCommand.SeekBookTime(70_000))
        session.handle(PlayerCommand.StartSleep(SleepChoice.EndOfChapter))
        settle()
        exactPause.reach()
        session.onPaused()

        navigator.seekToChapter(2, 0)
        settle()
        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(100_000)
    }

    @Test
    fun `a seek to a chapter's start after a pause does not rewind into the previous chapter`() = runTest {
        val session = loaded()
        session.play()
        engine.pause()
        session.onPaused()
        advanceTimeBy(60_000)

        session.handle(PlayerCommand.SeekBookTime(60_000))
        settle()
        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(60_000)
    }

    @Test
    fun `the same book placed again in its changed files is not left`() = runTest {
        val session = loaded()

        session.load(BookId("42"), fromStart = false, play = true, at = 33_000)
        settle()

        assertThat(left).isEmpty()
        assertThat(navigator.bookTimeMs()).isEqualTo(33_000)
    }

    @Test
    fun `an opened book is told, a closed one too`() = runTest {
        val told = mutableListOf<String>()
        val session = session()
        session.opened = { told += "opened ${it.bookId}" }
        session.closed = { told += "closed" }

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        session.handle(PlayerCommand.CloseBook)

        assertThat(told).containsExactly("opened 42", "closed").inOrder()
    }

    @Test
    fun `resuming the last book loads it, plays, and says when it is done`() = runTest {
        val session = session()
        session.resumable = { BookId("42") }
        var done = false

        session.resume(BookId("42"), { true }) { done = true }
        settle()

        assertThat(done).isTrue()
        assertThat(engine.items).hasSize(2)
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `only the stored last book can be resumed`() = runTest {
        val session = session()
        session.resumable = { BookId("41") }
        var done = false

        session.resume(BookId("42"), { true }) { done = true }
        settle()

        assertThat(done).isTrue()
        assertThat(engine.items).isEmpty()
        assertThat(audio.calls).doesNotContain("manifest 42")
    }

    @Test
    fun `a book opened paused over a playing one starts where it is, without a rewind`() = runTest {
        val session = loaded()
        // As the loop does: a pause tells the session.
        engine.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && !engine.playWhenReady) session.onPaused()
            }
        })
        audio.playbackStateResult = ApiResult.Success(
            app.tfdn.lirelle.core.library.model.PlaybackState("a2", 30_000, 0.0, false, null, "v1", "rev-1"),
        )

        session.handle(PlayerCommand.LoadBook(BookId("43"), play = false))
        settle()
        advanceTimeBy(60_000)
        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(130_000)
    }

    @Test
    fun `a last book resumed paused is loaded without starting`() = runTest {
        val session = session()
        session.resumable = { BookId("42") }

        session.resume(BookId("42"), { false }) {}
        settle()

        assertThat(engine.items).hasSize(2)
        assertThat(engine.wantsToPlay).isFalse()
    }

    @Test
    fun `a last book that cannot be read rejects the book, and says so`() = runTest {
        val session = session()
        session.resumable = { error("store unreadable") }
        val outcomes = mutableListOf<ResumeOutcome>()
        session.resumed = { outcomes += it }
        var done = false

        session.resume(BookId("42"), { true }) { done = true }
        settle()

        assertThat(done).isTrue()
        assertThat(outcomes).containsExactly(ResumeOutcome.Rejected)
        assertThat(engine.items).isEmpty()
    }

    @Test
    fun `play at the end keeps the book finished, a seek away from it does not`() = runTest {
        val session = loaded()
        session.onFinished()
        settle()

        session.play()
        assertThat(session.extras.value.finished).isNotNull()

        session.handle(PlayerCommand.SeekBookTime(60_000))
        settle()
        assertThat(session.extras.value.finished).isNull()
    }
}
