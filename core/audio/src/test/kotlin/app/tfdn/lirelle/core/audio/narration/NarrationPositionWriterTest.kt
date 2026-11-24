package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NarrationPositionWriterTest {

    private val start = Instant.parse("2026-11-15T10:00:00Z")
    private val repository = FakeReaderRepository()
    private val outbox = InMemoryOutbox()
    private val store = MemoryNarrationStore()
    private val changes = ReadingChanges()

    private fun TestScope.writer(narrations: NarrationStore = store): NarrationPositionWriter {
        val wall = Clock.fixed(start, ZoneOffset.UTC)
        val flusher = OutboxFlusher(outbox, repository, backgroundScope, wall, changes)
        return NarrationPositionWriter(narrations, outbox, flusher, MonotonicClock { currentTime })
    }

    /** The narration [millis] into the book, now. */
    private fun TestScope.at(millis: Long, fileId: FileId = FileId("12")) = LocalNarration(
        fileId = fileId,
        place = NarrationPlace(millis / 1_000.0, "ch1.xhtml#s${millis / 1_000}", 2),
        percent = millis / 1_000.0,
        at = start.plusMillis(currentTime),
    )

    private val sentSeconds: List<Double> get() = repository.savedNarrations.map { it.second.seconds }

    @Test
    fun `a tick is stored at once and the first one is sent`() = runTest {
        val writer = writer()
        val first = at(1_000)

        writer.onTick(first)
        runCurrent()

        assertThat(store.get(FileId("12"))).isEqualTo(first)
        assertThat(repository.savedNarrations).containsExactly(FileId("12") to first.place)
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `while ticking, a narration is sent at most every 5 seconds`() = runTest {
        val writer = writer()

        // A tick every half second for 12 seconds.
        repeat(Ticks) { tick ->
            writer.onTick(at(tick * TickMillis))
            runCurrent()
            advanceTimeBy(TickMillis.milliseconds)
        }
        runCurrent()

        assertThat(sentSeconds).containsExactly(0.0, 5.0, 10.0).inOrder()
        assertThat(store.get(FileId("12"))?.place?.seconds).isEqualTo((Ticks - 1) * TickMillis / 1_000.0)
    }

    @Test
    fun `flushNow stores and sends at once, whatever was sent just before`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()
        advanceTimeBy(1.seconds)
        writer.onTick(at(1_000))
        runCurrent()

        val paused = at(1_200)
        writer.flushNow(paused)
        runCurrent()

        assertThat(sentSeconds).containsExactly(0.0, 1.2).inOrder()
        assertThat(store.get(FileId("12"))).isEqualTo(paused)
    }

    @Test
    fun `the ticks tell the screens nothing, a flushNow once it is sent`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        writer.flushNow(at(1_200))
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a narration already on its way is not sent twice`() = runTest {
        val writer = writer()
        val first = at(0)
        writer.onTick(first)
        runCurrent()

        writer.flushNow(first)
        runCurrent()

        assertThat(repository.savedNarrations).hasSize(1)
    }

    @Test
    fun `a tick for another file is sent at once`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()

        writer.onTick(at(500, fileId = FileId("13")))
        runCurrent()

        assertThat(repository.savedNarrations.map { it.first }).containsExactly(FileId("12"), FileId("13")).inOrder()
    }

    @Test
    fun `the five seconds are timed by the tick, however late its turn comes`() = runTest {
        val writer = writer()

        writer.onTick(at(0), tickedAt = 0)
        advanceTimeBy(5_400)
        writer.onTick(at(5_000), tickedAt = 5_000)
        advanceTimeBy(4_600)
        writer.onTick(at(10_000), tickedAt = 10_000)
        runCurrent()

        assertThat(sentSeconds).containsExactly(0.0, 5.0, 10.0).inOrder()
    }

    @Test
    fun `a tick just short of five seconds is sent, one well short is not`() = runTest {
        val writer = writer()

        writer.onTick(at(0))
        advanceTimeBy(3_000)
        writer.onTick(at(3_000))
        advanceTimeBy(1_850)
        writer.onTick(at(4_850))
        runCurrent()

        assertThat(sentSeconds).containsExactly(0.0, 4.85).inOrder()
    }

    @Test
    fun `flushNow completes even when its caller is cancelled`() = runTest {
        val disk = CompletableDeferred<Unit>()
        val slowStore = object : NarrationStore by store {
            override suspend fun put(n: LocalNarration) {
                disk.await()
                store.put(n)
            }
        }
        val writer = writer(slowStore)
        val leaving = at(3_000)
        val job = launch { writer.flushNow(leaving) }
        runCurrent()

        job.cancel()
        disk.complete(Unit)
        runCurrent()

        assertThat(store.get(FileId("12"))).isEqualTo(leaving)
        assertThat(sentSeconds).containsExactly(3.0)
    }

    private companion object {
        const val Ticks = 24
        const val TickMillis = 500L
    }

    /** The narrations in memory, for one account. */
    private class MemoryNarrationStore : NarrationStore {
        private val narrations = mutableMapOf<FileId, LocalNarration>()

        override suspend fun get(fileId: FileId) = narrations[fileId]

        override suspend fun put(n: LocalNarration) {
            narrations[n.fileId] = n
        }
    }
}
