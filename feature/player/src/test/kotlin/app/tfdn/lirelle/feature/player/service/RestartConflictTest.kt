package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.audio.position.WrittenPlace
import app.tfdn.lirelle.core.audio.position.followServer
import app.tfdn.lirelle.core.audio.position.keepOurs
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.feature.player.controller.InMemoryPlaybackOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone listened offline after its last write, another device wrote meanwhile, and the app was
 * stopped: at the next start the queued position meets the other device's state, and the book opens.
 * The real flusher, store, outbox, conflicts and loader, against a fake server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RestartConflictTest {

    private val t0 = Instant.parse("2026-11-15T15:44:24Z")
    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }
    private val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(sampleBookDetail(id = "42")) }
    private val store = InMemoryPlaybackStore()
    private val outbox = InMemoryPlaybackOutbox()
    private val settings = InMemoryPlayerSettingsStore()

    /** The other device's state: the second file, 30 s in (2:10 of the book), written 33 s after our last write. */
    private val elsewhere = PlaybackState("a2", 30_000, 81.0, false, t0.plusSeconds(33), "v2", "rev-1")

    /** This device's latest position, listened to offline: 1:10 of the book, 66 s after its last write. */
    private val offline =
        LocalPlayback(BookId("42"), "a1", 70_000, 70_000, t0.plusSeconds(66), "op-offline", "v1", "rev-1")

    private lateinit var flusher: PlaybackFlusher

    /** What the process before the stop left on the device. */
    private suspend fun beforeTheStop() {
        store.setKnownVersion(BookId("42"), "v1")
        store.setLastWritten(BookId("42"), t0, WrittenPlace("a1", 40_000))
        store.put(offline)
        outbox.enqueue(offline)
        audio.playbackStateResult = ApiResult.Success(elsewhere)
    }

    /** The app starts again: a new flusher and conflicts over what was kept, the queued position sent at once. */
    private fun TestScope.restart(): ListeningConflicts {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val conflicts = ListeningConflicts(store, outbox, Clock.fixed(t0.plusSeconds(600), ZoneOffset.UTC)) {
            flusher.kick()
        }
        flusher = PlaybackFlusher(outbox, store, audio, scope, ReadingChanges())
            .also { it.writes = conflicts }
        flusher.kick()
        return conflicts
    }

    private fun loader(conflicts: ListeningConflicts) = BookLoader(
        audio, library, store, settings,
        waiting = { id -> outbox.positions.any { it.bookId == id } },
        standing = { id -> id in conflicts.standing.value },
    ) { "Part $it" }

    private fun saved(version: String) = ApiResult.Success(PlaybackWriteResult.Saved(elsewhere.copy(version = version)))

    private val conflict = ApiResult.Success(PlaybackWriteResult.Overtaken)

    @Test
    fun `the book opens at this device's own offline place, the other one offered, the server untouched`() = runTest {
        beforeTheStop()
        audio.saveResults = listOf(conflict)
        val conflicts = restart()

        val book = loader(conflicts).load(BookId("42"), fromStart = false)!!

        assertThat(book.startMs).isEqualTo(70_000)
        assertThat(conflicts.standing.value).containsExactly(BookId("42"), elsewhere)
        assertThat(audio.writes).hasSize(1)
        // The other device's version is not taken on: nothing of this device overwrites it unasked.
        assertThat(store.versions[BookId("42")]).isEqualTo("v1")
    }

    @Test
    fun `ignore then writes this device's offline place at once over the other device's version`() = runTest {
        beforeTheStop()
        audio.saveResults = listOf(conflict, saved("v3"))
        val conflicts = restart()
        loader(conflicts).load(BookId("42"), fromStart = false)

        conflicts.keepOurs(BookId("42"), version = "v2")

        val written = audio.writes.last().second
        assertThat(written.baseVersion).isEqualTo("v2")
        assertThat(written.assetId).isEqualTo("a1")
        assertThat(written.positionMs).isEqualTo(70_000)
        assertThat(store.versions[BookId("42")]).isEqualTo("v3")
    }

    @Test
    fun `go there then opens at the other device's place`() = runTest {
        beforeTheStop()
        audio.saveResults = listOf(conflict)
        val conflicts = restart()
        loader(conflicts).load(BookId("42"), fromStart = false)

        conflicts.followServer(BookId("42"), version = "v2")

        assertThat(loader(conflicts).load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(130_000)
    }

    @Test
    fun `after another restart, a conflict answered by go there opens at the other device's place`() = runTest {
        beforeTheStop()
        audio.saveResults = listOf(conflict)
        restart().followServer(BookId("42"), version = "v2")

        val conflicts = restart()

        assertThat(conflicts.standing.value).isEmpty()
        assertThat(loader(conflicts).load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(130_000)
    }

    @Test
    fun `with no conflict known since the restart, this device's newer place still opens`() = runTest {
        beforeTheStop()
        // The queued position was dropped before the stop: nothing waits, nothing stands in this process.
        outbox.positions.clear()
        val conflicts = restart()

        assertThat(loader(conflicts).load(BookId("42"), fromStart = false)!!.startMs).isEqualTo(70_000)
    }
}
