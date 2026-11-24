package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.audio.narration.toManifest
import app.tfdn.lirelle.core.audio.narration.trackOf
import app.tfdn.lirelle.core.audio.session.ListeningSessionClock
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The session loop playing a narration: its places go to the narration writer, its sessions to its file. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NarrationLoopTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val map = ReadAlongMaps.inventedMap()
    private val playlist = BookPlaylist.ofSegments(
        map.toManifest("The Long Road", emptyList(), emptyList(), hasCover = false),
        null,
        { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
    ) { "https://b/$it" }!!
    private val reader = FakeReaderRepository()
    private val outbox = InMemoryOutbox()
    private val store = InMemoryNarrationStore()
    private val sent = mutableListOf<Pair<FileId, ReadingSessionReport>>()

    private val sentPlaces get() = reader.savedNarrations.map { it.second }

    private fun TestScope.loop(): PlaybackSessionLoop {
        val monotonic = MonotonicClock { currentTime }
        val wall = VirtualClock { currentTime }
        val flusher = OutboxFlusher(outbox, reader, backgroundScope, wall, ReadingChanges())
        val writer = NarrationPositionWriter(store, outbox, flusher, monotonic)
        val timer = SleepTimer(monotonic)
        val sleep = SleepDriver(engine, navigator, timer, FakeExactPause())
        val sessions =
            ListeningSessions({ ListeningSessionClock(monotonic, wall) }) { file, report -> sent += file to report }
        val events = object : PlaybackSessionLoop.Events {
            override fun onTick() = Unit

            override fun onPaused() = Unit

            override fun onFinished() = Unit
        }
        return PlaybackSessionLoop(engine, navigator, sleep, sessions, backgroundScope, events).also {
            navigator.open(playlist, 0)
            it.positions = BookPositions(playlist, NarrationSink(writer, FileId("1"), map, wall))
            engine.durationsMs = listOf(15_000, 10_000)
            engine.engineState = Player.STATE_READY
            it.start()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun TestScope.settle() {
        idle()
        runCurrent()
    }

    private fun TestScope.play() {
        engine.wantsToPlay = true
        engine.update()
        settle()
    }

    private fun TestScope.pause() {
        engine.wantsToPlay = false
        engine.update()
        settle()
    }

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
    fun `while a narration plays, its place is sent every 5 seconds with the sentence heard`() = runTest {
        loop()
        play()

        listen(12_000)
        runCurrent()

        assertThat(sentPlaces.map { it.seconds }).containsExactly(0.0, 5.0, 10.0).inOrder()
        assertThat(sentPlaces.map { it.fragment })
            .containsExactly("text/one.xhtml#s1", "text/one.xhtml#s2", "text/one.xhtml#s3").inOrder()
        assertThat(reader.savedNarrations.map { it.first }.toSet()).containsExactly(FileId("1"))
        // Kept on the device at each store, the latest one now.
        assertThat(store.get(FileId("1"))!!.place.seconds).isEqualTo(10.0)
    }

    @Test
    fun `a pause sends the narration's place at once, and the listening session lands on the read-along file`() =
        runTest {
            loop()
            play()
            listen(12_000)
            val before = sentPlaces.size

            pause()
            runCurrent()

            assertThat(sentPlaces).hasSize(before + 1)
            assertThat(sentPlaces.last().seconds).isEqualTo(12.0)
            assertThat(store.get(FileId("1"))!!.percent).isEqualTo(48.0)
            assertThat(sent.map { it.first }).containsExactly(FileId("1"))
        }

    @Test
    fun `a seek sends the narration's new place at once`() = runTest {
        loop()

        // Into the second segment, which starts 2 s into its file: positions are from the segment's start.
        engine.seekTo(1, 5_000)
        settle()

        assertThat(sentPlaces.single().seconds).isEqualTo(20.0)
        assertThat(sentPlaces.single().fragment).isEqualTo("text/three.xhtml#s5")
        assertThat(sentPlaces.single().sectionIndex).isEqualTo(3)
    }

    @Test
    fun `the positions of another playlist never go to the narration's sink`() = runTest {
        val loop = loop()
        val audiobook = BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!
        navigator.open(audiobook, 0)

        engine.seekTo(1, 5_000)
        settle()
        loop.flush()
        runCurrent()

        assertThat(reader.savedNarrations).isEmpty()
    }
}
