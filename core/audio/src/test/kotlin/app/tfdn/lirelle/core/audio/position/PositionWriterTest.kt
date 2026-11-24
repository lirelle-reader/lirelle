package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionWriterTest {

    private val start = Instant.parse("2026-11-15T10:00:00Z")
    private val repository = FakeAudioRepository().apply {
        saveResults = listOf(
            ApiResult.Success(
                PlaybackWriteResult.Saved(PlaybackState("aud_one", 0, 1.0, false, start, "v1", SampleContent)),
            ),
        )
    }
    private val outbox = InMemoryPlaybackOutbox()
    private val store = InMemoryPlaybackStore()

    private val changes = ReadingChanges()

    private fun TestScope.writer(): PositionWriter {
        val flusher = PlaybackFlusher(outbox, store, repository, backgroundScope, changes)
        flusher.clock = MonotonicClock { currentTime }
        return PositionWriter(store, outbox, flusher, MonotonicClock { currentTime })
    }

    /**
     * A new position, as the player makes one at each tick: [millis] into the book, now. The
     * player has no operation id to give: it gives the same one each time.
     */
    private fun TestScope.at(millis: Long) = LocalPlayback(
        bookId = SampleBook,
        assetId = "aud_one",
        positionMs = millis,
        bookTimeMs = millis,
        at = start.plusMillis(currentTime),
        operationId = "from-the-player",
        contentVersion = SampleContent,
    )

    private val sentIds: List<String> get() = repository.writes.map { it.second.operationId }

    @Test
    fun `every new position is given an operation id of its own`() = runTest {
        val writer = writer()

        writer.flushNow(at(0))
        runCurrent()
        writer.flushNow(at(1_000))
        runCurrent()

        assertThat(sentIds).hasSize(2)
        assertThat(sentIds.toSet()).hasSize(2)
        assertThat(sentIds).doesNotContain("from-the-player")
    }

    @Test
    fun `a tick is stored at once and the first one is sent`() = runTest {
        val writer = writer()
        val first = at(1_000)

        writer.onTick(first)
        runCurrent()

        assertThat(store.get(SampleBook)?.positionMs).isEqualTo(1_000)
        assertThat(sentIds).containsExactly(store.get(SampleBook)?.operationId)
    }

    @Test
    fun `while ticking, a position is sent at most every 5 seconds`() = runTest {
        val writer = writer()

        // A tick every half second for 12 seconds.
        repeat(Ticks) { tick ->
            writer.onTick(at(tick * TickMillis))
            runCurrent()
            advanceTimeBy(TickMillis.milliseconds)
        }
        runCurrent()

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 5_000L, 10_000L).inOrder()
        assertThat(store.get(SampleBook)?.positionMs).isEqualTo((Ticks - 1) * TickMillis)
    }

    @Test
    fun `flushNow sends at once, whatever was sent just before`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()
        advanceTimeBy(1.seconds)
        writer.onTick(at(1_000))
        runCurrent()

        val paused = at(1_200)
        writer.flushNow(paused)
        runCurrent()

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 1_200L).inOrder()
        // Made over the version the first write got.
        assertThat(store.get(SampleBook)?.copy(operationId = paused.operationId))
            .isEqualTo(paused.copy(baseVersion = "v1"))
    }

    @Test
    fun `the ticks are not told to the screens, the position flushed after them is`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()
        advanceTimeBy(5.seconds)
        writer.onTick(at(5_000))
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        writer.flushNow(at(6_000))
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a position already on its way is not sent twice`() = runTest {
        val writer = writer()
        val first = at(0)
        writer.onTick(first)
        runCurrent()

        writer.flushNow(first)
        writer.flushNow(first.copy(operationId = "another"))
        runCurrent()

        assertThat(repository.writes).hasSize(1)
    }

    @Test
    fun `a tick held back by the 5 seconds is sent by flushNow with the id it was stored with`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()
        advanceTimeBy(1.seconds)
        val held = at(1_000)
        writer.onTick(held)
        runCurrent()
        val stored = store.get(SampleBook)?.operationId

        writer.flushNow(held)
        runCurrent()

        assertThat(sentIds).hasSize(2)
        assertThat(sentIds.last()).isEqualTo(stored)
    }

    @Test
    fun `positions are made over the version known for the book`() = runTest {
        store.setKnownVersion(SampleBook, "v4")
        val writer = writer()

        writer.flushNow(at(0))

        assertThat(store.get(SampleBook)?.baseVersion).isEqualTo("v4")
        assertThat(outbox.positions.single().baseVersion).isEqualTo("v4")
    }

    @Test
    fun `a book never written to is created`() = runTest {
        val writer = writer()

        writer.flushNow(at(0).copy(baseVersion = "v9"))

        assertThat(outbox.positions.single().baseVersion).isNull()
    }

    @Test
    fun `a tick for another book is sent at once`() = runTest {
        val writer = writer()
        writer.onTick(at(0))
        runCurrent()

        writer.onTick(at(500).copy(bookId = OtherBook))
        runCurrent()

        assertThat(repository.writes.map { it.first }).containsExactly(SampleBook, OtherBook).inOrder()
    }

    @Test
    fun `flushNow completes even when its caller is cancelled`() = runTest {
        val disk = CompletableDeferred<Unit>()
        val slowStore = object : PlaybackStore by store {
            override suspend fun put(position: LocalPlayback) {
                disk.await()
                store.put(position)
            }
        }
        val flusher = PlaybackFlusher(outbox, store, repository, backgroundScope, ReadingChanges())
        val writer = PositionWriter(slowStore, outbox, flusher, MonotonicClock { currentTime })
        val leaving = at(3_000)
        val job = launch { writer.flushNow(leaving) }
        runCurrent()

        job.cancel()
        disk.complete(Unit)
        runCurrent()

        assertThat(store.get(SampleBook)?.positionMs).isEqualTo(3_000)
        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(3_000L)
    }

    @Test
    fun `a store slow to answer does not stretch the five seconds`() = runTest {
        val delays = ArrayDeque(listOf(300L, 100L, 100L))
        val slowStore = object : PlaybackStore by store {
            override suspend fun put(position: LocalPlayback) {
                delay(delays.removeFirstOrNull() ?: 0)
                store.put(position)
            }
        }
        val flusher = PlaybackFlusher(outbox, store, repository, backgroundScope, changes)
        val writer = PositionWriter(slowStore, outbox, flusher, MonotonicClock { currentTime })

        writer.onTick(at(0))
        advanceTimeBy(5_000 - currentTime)
        writer.onTick(at(5_000))
        runCurrent()

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 5_000L).inOrder()
    }

    @Test
    fun `the five seconds are timed by the tick, however late its turn comes`() = runTest {
        val writer = writer()

        writer.onTick(at(0), tickedAt = 0)
        // Ticked at 5 s, its turn came only at 5.4 s.
        advanceTimeBy(5_400)
        writer.onTick(at(5_000), tickedAt = 5_000)
        advanceTimeBy(4_600)
        writer.onTick(at(10_000), tickedAt = 10_000)
        runCurrent()

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 5_000L, 10_000L).inOrder()
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

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 4_850L).inOrder()
    }

    @Test
    fun `with a failing server, ticks do not cut the growing wait short, a settling does`() = runTest {
        repository.saveResults = listOf(ApiResult.Failure(ApiError.ServerFailed))
        val writer = writer()

        // A tick every 5 seconds for a minute: tried at 0, then after waiting 5, 10 and 20 seconds.
        repeat(12) { tick ->
            writer.onTick(at(tick * 5_000L))
            runCurrent()
            advanceTimeBy(5_000)
        }
        runCurrent()
        assertThat(repository.writes).hasSize(4)

        writer.flushNow(at(60_500))
        runCurrent()

        assertThat(repository.writes).hasSize(5)
        assertThat(repository.writes.last().second.positionMs).isEqualTo(60_500)
    }

    @Test
    fun `a tick while nothing is being sent starts a flush, one during a flush is sent after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writer = writer()
        repository.gate = gate
        writer.onTick(at(0))
        runCurrent()

        advanceTimeBy(5_000)
        writer.onTick(at(5_000))
        repository.gate = null
        gate.complete(Unit)
        runCurrent()

        assertThat(repository.writes.map { it.second.positionMs }).containsExactly(0L, 5_000L).inOrder()
        assertThat(outbox.positions).isEmpty()
    }

    private companion object {
        const val Ticks = 24
        const val TickMillis = 500L
    }
}
