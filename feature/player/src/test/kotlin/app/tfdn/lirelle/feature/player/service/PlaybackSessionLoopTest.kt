package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.audio.session.ListeningSessionClock
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Records what is handed to it and has never anything to send. */
class RecordingOutbox : PlaybackOutbox {
    val enqueued = mutableListOf<LocalPlayback>()

    override suspend fun enqueue(p: LocalPlayback) {
        enqueued += p
    }

    override suspend fun pending(): List<LocalPlayback> = emptyList()

    override suspend fun markSent(p: LocalPlayback) = Unit

    override suspend fun replace(old: LocalPlayback, new: LocalPlayback) = Unit
}

/** Counts what is written to the device's store. */
class CountingPlaybackStore(private val inner: InMemoryPlaybackStore = InMemoryPlaybackStore()) :
    app.tfdn.lirelle.core.audio.position.PlaybackStore by inner {
    var puts = 0

    override suspend fun put(position: LocalPlayback) {
        puts++
        inner.put(position)
    }
}

/** A wall clock that follows the test's virtual time. */
class VirtualClock(private val now: () -> Long) : Clock() {
    override fun instant(): Instant = Instant.parse("2026-11-15T21:00:00Z").plusMillis(now())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

class FakeExactPause : ExactPause {
    var target: Long? = null
    private var action: (() -> Unit)? = null

    override fun at(bookTimeMs: Long, action: () -> Unit) {
        target = bookTimeMs
        this.action = action
    }

    override fun cancel() {
        target = null
        action = null
    }

    fun reach() = action?.invoke()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSessionLoopTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val outbox = RecordingOutbox()
    private val exactPause = FakeExactPause()
    private val sent = mutableListOf<Pair<FileId, ReadingSessionReport>>()
    private val heard = mutableListOf<String>()
    private val playlist = BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!
    private val store = CountingPlaybackStore()
    private lateinit var timer: SleepTimer

    private fun TestScope.loop(): PlaybackSessionLoop {
        val monotonic = MonotonicClock { currentTime }
        val flusher =
            PlaybackFlusher(outbox, store, FakeAudioRepository(), backgroundScope, ReadingChanges())
        timer = SleepTimer(monotonic)
        val sleep = SleepDriver(engine, navigator, timer, exactPause)
        val wall = VirtualClock { currentTime }
        val sessions =
            ListeningSessions({ ListeningSessionClock(monotonic, wall) }) { file, report -> sent += file to report }
        val events = object : PlaybackSessionLoop.Events {
            override fun onTick() = Unit

            override fun onPaused() {
                heard += "paused"
            }

            override fun onFinished() {
                heard += "finished"
            }
        }
        val writer = PositionWriter(store, outbox, flusher, monotonic)
        return PlaybackSessionLoop(engine, navigator, sleep, sessions, backgroundScope, events).also {
            navigator.open(playlist, 10_000)
            it.positions = BookPositions(playlist, PlaybackSink(writer, playlist, wall))
            engine.durationsMs = listOf(100_000, 60_000)
            engine.engineState = Player.STATE_READY
            it.start()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun TestScope.play() {
        engine.wantsToPlay = true
        engine.update()
        idle()
        runCurrent()
    }

    private fun TestScope.pause() {
        engine.wantsToPlay = false
        engine.update()
        idle()
        runCurrent()
    }

    /** Playback moves on by [millis], the engine's position with it. */
    private fun TestScope.listen(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 500)
            engine.positionMs += step
            engine.update()
            advanceTimeBy(step)
            runCurrent()
            left -= step
        }
        idle()
    }

    @Test
    fun `while playing, the position is sent every 5 seconds, not every tick`() = runTest {
        loop()
        play()

        listen(12_000)

        assertThat(outbox.enqueued.map { it.bookTimeMs / 1_000 }).containsExactly(10L, 15L, 20L).inOrder()
        assertThat(outbox.enqueued.last().assetId).isEqualTo("a1")
        assertThat(outbox.enqueued.last().contentVersion).isEqualTo("rev-1")
    }

    @Test
    fun `a pause sends the position at once, and the listening session so far`() = runTest {
        loop()
        play()
        listen(12_000)
        val before = outbox.enqueued.size

        pause()

        assertThat(outbox.enqueued).hasSize(before + 1)
        assertThat(outbox.enqueued.last().bookTimeMs).isEqualTo(22_000)
        assertThat(heard).containsExactly("paused")
        assertThat(sent.map { it.first }).containsExactly(FileId("101"))
    }

    @Test
    fun `leaving the book for another sends its position at once, then its listening session`() = runTest {
        // One audiobook at a time (spec 11): the session leaves the current book before the next replaces it.
        val loop = loop()
        play()
        listen(12_000)
        val before = outbox.enqueued.size

        loop.leave()
        runCurrent()

        assertThat(outbox.enqueued).hasSize(before + 1)
        assertThat(outbox.enqueued.last().bookTimeMs).isEqualTo(22_000)
        assertThat(sent.map { it.first }).containsExactly(FileId("101"))
    }

    @Test
    fun `a seek sends the new position at once`() = runTest {
        loop()

        engine.seekTo(1, 30_000)
        idle()
        runCurrent()

        assertThat(outbox.enqueued.single().bookTimeMs).isEqualTo(130_000)
        assertThat(outbox.enqueued.single().assetId).isEqualTo("a2")
    }

    @Test
    fun `a chapter change while playing sends the position at once`() = runTest {
        loop()
        engine.positionMs = 58_000
        play()
        listen(1_000)
        val before = outbox.enqueued.size

        listen(1_500)

        assertThat(outbox.enqueued.size).isEqualTo(before + 1)
        assertThat(outbox.enqueued.last().bookTimeMs).isAtLeast(60_000)
    }

    @Test
    fun `a timed sleep fades the volume out and pauses, the volume back for next time`() = runTest {
        loop()
        play()
        exactPause.cancel()
        // The timer starts on the player's tick, like the sleep sheet does.
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.Minutes(1), fade = true)

        listen(55_000)
        assertThat(engine.engineVolume).isLessThan(1f)
        assertThat(engine.wantsToPlay).isTrue()

        listen(6_000)
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(engine.engineVolume).isEqualTo(1f)
        assertThat(timer.resumePoint()).isNotNull()
    }

    @Test
    fun `the end of the chapter pauses exactly there`() = runTest {
        loop()
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(1_000)

        assertThat(exactPause.target).isEqualTo(60_000)
        exactPause.reach()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `at the end of the book the position is sent, the session closed, and the book finished`() = runTest {
        loop()
        play()
        listen(15_000)
        val before = outbox.enqueued.size

        engine.index = 1
        engine.positionMs = 60_000
        engine.wantsToPlay = true
        engine.engineState = Player.STATE_ENDED
        engine.update()
        idle()
        runCurrent()

        assertThat(outbox.enqueued.size).isEqualTo(before + 1)
        assertThat(outbox.enqueued.last().bookTimeMs).isEqualTo(160_000)
        assertThat(heard).containsExactly("finished")
        assertThat(sent).hasSize(1)
        advanceTimeBy(10.seconds)
    }

    @Test
    fun `while playing, the device's store is written every 5 seconds, not every tick`() = runTest {
        loop()
        play()

        listen(12_000)

        assertThat(store.puts).isEqualTo(3)
    }

    @Test
    fun `a stall for data stops the listening clock only, without a write or a pause`() = runTest {
        loop()
        play()
        listen(15_000)
        val before = outbox.enqueued.size

        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        idle()
        runCurrent()

        assertThat(sent).isEmpty()
        assertThat(outbox.enqueued).hasSize(before)
        assertThat(heard).doesNotContain("paused")
    }

    @Test
    fun `a pause while already held (a long interruption, the network) counts as a pause`() = runTest {
        loop()
        play()
        listen(5_000)
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        idle()
        runCurrent()

        pause()

        assertThat(heard).containsExactly("paused")
    }

    @Test
    fun `the end of a chapter that ends its file stops when the next file starts, if not before`() = runTest {
        loop()
        engine.positionMs = 70_000
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(500)
        assertThat(exactPause.target).isEqualTo(100_000)

        engine.positionMs = 100_000
        engine.update()
        idle()
        engine.index = 1
        engine.positionMs = 0
        engine.update()
        idle()
        runCurrent()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `next chapter pressed is followed by the end of chapter, not taken for the end crossed`() = runTest {
        loop()
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(500)
        assertThat(exactPause.target).isEqualTo(60_000)

        navigator.nextChapter()
        idle()
        listen(500)

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(exactPause.target).isEqualTo(100_000)
    }

    @Test
    fun `paused just before the end of the chapter, played again, it still pauses at that end`() = runTest {
        loop()
        engine.positionMs = 59_000
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(500)
        pause()
        play()

        listen(1_000)

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `paused just past the end of the chapter, played again, it waits for the next end`() = runTest {
        loop()
        engine.positionMs = 59_000
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(500)
        // The user paused right as the chapter ended, before a tick saw the next one.
        engine.positionMs = 60_200
        pause()

        play()
        listen(500)

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(exactPause.target).isEqualTo(100_000)
    }

    @Test
    fun `a stall at the end of the chapter is no pause, the end crossed pauses`() = runTest {
        loop()
        engine.positionMs = 59_000
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)
        listen(500)
        // The next file is not there yet: waiting for data just past the end.
        engine.positionMs = 60_200
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        idle()
        runCurrent()

        engine.engineState = Player.STATE_READY
        engine.update()
        idle()
        runCurrent()

        assertThat(engine.wantsToPlay).isFalse()
    }

    @Test
    fun `the end of the last chapter lets the book end, and the book is finished`() = runTest {
        loop()
        engine.index = 1
        engine.positionMs = 50_000
        engine.update()
        play()
        SleepDriver(engine, navigator, timer, exactPause).start(SleepChoice.EndOfChapter, fade = true)

        listen(9_500)
        assertThat(exactPause.target).isNull()
        assertThat(engine.wantsToPlay).isTrue()

        engine.engineState = Player.STATE_ENDED
        engine.update()
        idle()
        runCurrent()

        assertThat(heard).containsExactly("finished")
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `a playback error that is no stall sends the position and declares the listening`() = runTest {
        loop()
        play()
        listen(15_500)
        val before = outbox.enqueued.size

        engine.failure = androidx.media3.common.PlaybackException(
            "forbidden", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        engine.update()
        idle()
        runCurrent()

        assertThat(outbox.enqueued).hasSize(before + 1)
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `a playback error after a stall sends the position and declares the listening`() = runTest {
        loop()
        play()
        listen(15_500)
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        idle()
        runCurrent()
        val before = outbox.enqueued.size

        engine.failure = androidx.media3.common.PlaybackException(
            "forbidden", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        engine.update()
        idle()
        runCurrent()

        assertThat(outbox.enqueued).hasSize(before + 1)
        assertThat(sent).hasSize(1)
    }
}
