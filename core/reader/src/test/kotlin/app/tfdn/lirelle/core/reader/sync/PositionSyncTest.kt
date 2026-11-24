package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionSyncTest {

    private val clocks = TestClocks()
    private val t0 = clocks.now
    private val here = "epubcfi(/6/4!/4/2/1:0)"
    private val there = "epubcfi(/6/18!/4/2/1:0)"
    private val repository = FakeReaderRepository()
    private val store = InMemoryPositionStore()
    private val outbox = InMemoryOutbox()
    private var kicks = 0

    private fun TestScope.sync() = PositionSync(
        fileId = ReadFile,
        scope = backgroundScope,
        repository = repository,
        store = store,
        outbox = outbox,
        kick = { kicks++ },
        wall = clocks.wall,
    )

    private fun serverHas(cfi: String?, percent: Double, secondsFromStart: Long?) {
        val at = secondsFromStart?.let(t0::plusSeconds)
        repository.positionResult = ApiResult.Success(ReadingPosition(ReadFile, cfi, percent, at))
    }

    private fun local(cfi: String?, percent: Double, secondsFromStart: Long) =
        LocalPosition(ReadFile, cfi, percent, t0.plusSeconds(secondsFromStart))

    @Test
    fun `restore gives the server's position and keeps it on the device`() = runTest {
        serverHas(there, 40.0, secondsFromStart = -3600)
        store.put(local(here, 10.0, secondsFromStart = -7200))

        val restored = sync().restore()

        assertThat(restored).isEqualTo(RestoredPosition(there, 40.0))
        assertThat(restored.fraction).isEqualTo(0.4)
        assertThat(store.get(ReadFile)).isEqualTo(local(there, 40.0, secondsFromStart = -3600))
        assertThat(repository.calls).containsExactly("position $ReadFile")
    }

    @Test
    fun `a file never opened starts at the beginning and forgets nothing useful`() = runTest {
        serverHas(null, 0.0, secondsFromStart = null)

        assertThat(sync().restore()).isEqualTo(RestoredPosition(null, 0.0))
        assertThat(store.positions).isEmpty()
    }

    @Test
    fun `restore gives the device's position when the server cannot be reached`() = runTest {
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        store.put(local(here, 10.0, secondsFromStart = -7200))

        assertThat(sync().restore()).isEqualTo(RestoredPosition(here, 10.0))
    }

    @Test
    fun `restore starts at the beginning when nothing is known anywhere`() = runTest {
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(sync().restore()).isEqualTo(RestoredPosition(null, 0.0))
    }

    @Test
    fun `restore gives the device's position when it is newer and unsent, and has it sent`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -7200)
        val unsent = local(there, 40.0, secondsFromStart = -3600)
        store.put(unsent)
        outbox.enqueuePosition(unsent)

        assertThat(sync().restore()).isEqualTo(RestoredPosition(there, 40.0))
        assertThat(outbox.positions).containsExactly(unsent)
        assertThat(store.get(ReadFile)).isEqualTo(unsent)
        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `an unsent position is used when the server has none`() = runTest {
        serverHas(null, 0.0, secondsFromStart = null)
        val unsent = local(there, 40.0, secondsFromStart = -3600)
        outbox.enqueuePosition(unsent)

        assertThat(sync().restore()).isEqualTo(RestoredPosition(there, 40.0))
    }

    @Test
    fun `an unsent position older than the server's gives way and is not sent any more`() = runTest {
        serverHas(there, 40.0, secondsFromStart = -3600)
        val stale = local(here, 10.0, secondsFromStart = -7200)
        store.put(stale)
        outbox.enqueuePosition(stale)
        outbox.enqueuePosition(stale.copy(fileId = OtherFile))

        assertThat(sync().restore()).isEqualTo(RestoredPosition(there, 40.0))
        assertThat(outbox.positions).containsExactly(stale.copy(fileId = OtherFile))
    }

    @Test
    fun `a device position already sent does not win over the server's`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -7200)
        store.put(local(there, 40.0, secondsFromStart = -3600))

        assertThat(sync().restore()).isEqualTo(RestoredPosition(here, 10.0))
    }

    @Test
    fun `starting over opens at the beginning, whatever the server and the device hold`() = runTest {
        serverHas(there, 100.0, secondsFromStart = -3600)
        store.put(local(there, 100.0, secondsFromStart = -3600))

        val restored = sync().restore(fromStart = true)

        assertThat(restored).isEqualTo(RestoredPosition(null, 0.0))
        assertThat(store.get(ReadFile)).isEqualTo(local(there, 100.0, secondsFromStart = -3600))
        assertThat(outbox.positions).isEmpty()
        assertThat(kicks).isEqualTo(0)
    }

    @Test
    fun `starting over, the place the server held is not reported, a newer one is`() = runTest {
        serverHas(there, 100.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore(fromStart = true)
        sync.onRelocated(here, 0.0, moved = false)

        assertThat(sync.newerOnServer()).isNull()

        serverHas(there, 40.0, secondsFromStart = 60)
        assertThat(sync.newerOnServer()).isEqualTo(ReadingPosition(ReadFile, there, 40.0, t0.plusSeconds(60)))
    }

    @Test
    fun `starting over without the server, the place the device was left at is not reported later`() = runTest {
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        store.put(local(there, 100.0, secondsFromStart = -3600))
        val sync = sync()

        assertThat(sync.restore(fromStart = true)).isEqualTo(RestoredPosition(null, 0.0))
        sync.onRelocated(here, 0.0, moved = false)

        // The server is back, and dates with its own clock what this device had sent it.
        serverHas(there, 100.0, secondsFromStart = 600)
        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `starting over, a position still to be sent is sent, and is not reported when it comes back`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -7200)
        val unsent = local(there, 100.0, secondsFromStart = -3600)
        store.put(unsent)
        outbox.enqueuePosition(unsent)
        val sync = sync()

        assertThat(sync.restore(fromStart = true)).isEqualTo(RestoredPosition(null, 0.0))
        sync.onRelocated(here, 0.0, moved = false)

        assertThat(outbox.positions).containsExactly(unsent)
        assertThat(kicks).isEqualTo(1)
        serverHas(there, 100.0, secondsFromStart = 600)
        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `the place a book opens at is neither stored nor sent`() = runTest {
        serverHas(null, 0.0, secondsFromStart = null)
        val sync = sync()
        sync.restore()

        sync.onRelocated(here, 0.1, moved = false)
        advanceTimeBy(1.minutes)
        sync.flushNow()

        assertThat(store.positions).isEmpty()
        assertThat(outbox.log).isEmpty()
        assertThat(kicks).isEqualTo(0)
    }

    @Test
    fun `a move is stored at once and enqueued after two quiet seconds`() = runTest {
        val sync = sync()
        clocks.advance(90.seconds)

        sync.onRelocated(there, 0.4, moved = true)

        val moved = local(there, 40.0, secondsFromStart = 90)
        assertThat(store.get(ReadFile)).isEqualTo(moved)
        advanceTimeBy(1999.milliseconds)
        runCurrent()
        assertThat(outbox.positions).isEmpty()
        assertThat(kicks).isEqualTo(0)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertThat(outbox.positions).containsExactly(moved)
        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `the percentage sent is the fraction times a hundred`() = runTest {
        val sync = sync()

        sync.onRelocated(there, 0.123, moved = true)
        sync.flushNow()

        assertThat(outbox.positions.single().percent).isWithin(Tiny).of(12.3)
        assertThat(outbox.positions.single().cfi).isEqualTo(there)
    }

    @Test
    fun `a fraction out of range is brought back into it`() = runTest {
        val sync = sync()

        sync.onRelocated(there, 1.0000001, moved = true)

        assertThat(store.get(ReadFile)!!.percent).isEqualTo(100.0)
    }

    @Test
    fun `page turns in a row send only the last place, two seconds after the last one`() = runTest {
        val sync = sync()

        sync.onRelocated(here, 0.10, moved = true)
        advanceTimeBy(1500.milliseconds)
        sync.onRelocated(there, 0.11, moved = true)
        advanceTimeBy(1500.milliseconds)
        runCurrent()
        assertThat(outbox.log).isEmpty()

        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertThat(outbox.log).containsExactly("position $ReadFile")
        assertThat(outbox.positions.single().cfi).isEqualTo(there)
    }

    @Test
    fun `flushNow enqueues at once, and the wait that was running sends nothing more`() = runTest {
        val sync = sync()
        sync.onRelocated(there, 0.4, moved = true)

        sync.flushNow()

        assertThat(outbox.positions.single().cfi).isEqualTo(there)
        assertThat(kicks).isEqualTo(1)
        advanceTimeBy(1.minutes)
        sync.flushNow()
        assertThat(outbox.log).hasSize(1)
        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `flushNow goes through even when its caller is being cancelled`() = runTest {
        val sync = sync()
        sync.onRelocated(there, 0.4, moved = true)

        val leaving = launch {
            cancel()
            sync.flushNow()
        }
        leaving.join()

        assertThat(outbox.positions.single().cfi).isEqualTo(there)
    }

    @Test
    fun `back at the front, a newer position elsewhere on the server is reported`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        sync.onRelocated(here, 0.1, moved = false)

        assertThat(sync.newerOnServer()).isNull()

        serverHas(there, 40.0, secondsFromStart = 60)
        assertThat(sync.newerOnServer()).isEqualTo(ReadingPosition(ReadFile, there, 40.0, t0.plusSeconds(60)))
    }

    @Test
    fun `no newer position is reported when the server cannot be reached`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()

        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `our own move coming back from the server is not reported`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        clocks.advance(30.seconds)
        sync.onRelocated(there, 0.4, moved = true)
        sync.flushNow()
        sync.onRelocated(here, 0.41, moved = true)

        // Stamped by a server whose clock is ahead.
        serverHas(there, 40.0, secondsFromStart = 600)

        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `an ignored position is not reported again until a still newer one appears`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        serverHas(there, 40.0, secondsFromStart = 60)

        sync.ignore(sync.newerOnServer()!!)

        assertThat(sync.newerOnServer()).isNull()
        serverHas(there, 45.0, secondsFromStart = 120)
        assertThat(sync.newerOnServer()!!.percent).isEqualTo(45.0)
    }

    @Test
    fun `going to the reported position keeps it on the device and drops what was still to be sent`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        clocks.advance(10.seconds)
        sync.onRelocated(here, 0.11, moved = true)
        serverHas(there, 40.0, secondsFromStart = 60)
        val offered = sync.newerOnServer()!!

        sync.accept(offered)
        advanceTimeBy(1.minutes)
        sync.flushNow()

        assertThat(store.get(ReadFile)).isEqualTo(local(there, 40.0, secondsFromStart = 60))
        assertThat(outbox.log).isEmpty()
        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `the server's older position is not offered over the unsent one the book opened at`() = runTest {
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val unsent = local(there, 40.0, secondsFromStart = -3600)
        store.put(unsent)
        outbox.enqueuePosition(unsent)
        val sync = sync()
        sync.restore()
        sync.onRelocated(there, 0.4, moved = false)

        // The network is back before the queue went out: the server still holds what it had.
        serverHas(here, 10.0, secondsFromStart = -7200)

        assertThat(sync.newerOnServer()).isNull()
        assertThat(outbox.positions).containsExactly(unsent)
        serverHas(here, 10.0, secondsFromStart = -60)
        assertThat(sync.newerOnServer()!!.percent).isEqualTo(10.0)
    }

    @Test
    fun `a look elsewhere is neither stored nor sent, and is not reported as the server's newer place`() = runTest {
        serverHas(here, 10.0, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        sync.onRelocated(here, 0.11, moved = true)
        sync.flushNow()

        sync.onRelocated(there, 0.4, moved = true, peek = true)
        advanceTimeBy(1.minutes)
        runCurrent()

        assertThat(store.get(ReadFile)!!.cfi).isEqualTo(here)
        assertThat(outbox.log).hasSize(1)
        serverHas(there, 40.0, secondsFromStart = 600)
        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `a move made just before a look elsewhere is still sent after its quiet period`() = runTest {
        val sync = sync()
        sync.onRelocated(here, 0.11, moved = true)
        advanceTimeBy(1.seconds)

        sync.onRelocated(there, 0.4, moved = true, peek = true)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertThat(outbox.positions.single().cfi).isEqualTo(here)
    }

    @Test
    fun `coming back from a look elsewhere stores and sends nothing`() = runTest {
        val sync = sync()
        sync.onRelocated(here, 0.11, moved = true)
        sync.flushNow()
        val kept = store.get(ReadFile)

        sync.onRelocated(there, 0.4, moved = true, peek = true)
        clocks.advance(30.seconds)
        sync.onReturned(here, 0.11)
        advanceTimeBy(1.minutes)
        sync.flushNow()

        assertThat(store.get(ReadFile)).isEqualTo(kept)
        assertThat(outbox.log).hasSize(1)
    }

    @Test
    fun `reading on from a look elsewhere makes the place read the position`() = runTest {
        val sync = sync()
        sync.onRelocated(here, 0.11, moved = true)
        sync.flushNow()

        sync.onRelocated(there, 0.4, moved = true, peek = true)
        sync.onRelocated(there, 0.41, moved = true)

        assertThat(store.get(ReadFile)!!.percent).isWithin(Tiny).of(41.0)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertThat(outbox.positions.single().percent).isWithin(Tiny).of(41.0)
    }

    @Test
    fun `leaving on a look elsewhere sends it, once`() = runTest {
        val sync = sync()
        sync.onRelocated(here, 0.11, moved = true)
        sync.flushNow()

        sync.onRelocated(there, 0.4, moved = true, peek = true)
        sync.flushNow()
        sync.flushNow()

        assertThat(store.get(ReadFile)!!.cfi).isEqualTo(there)
        assertThat(outbox.log).hasSize(2)
        assertThat(outbox.positions.single().cfi).isEqualTo(there)
    }

    @Test
    fun `coming back after the look elsewhere was sent sends the place come back to`() = runTest {
        val sync = sync()
        sync.onRelocated(here, 0.11, moved = true)
        sync.flushNow()
        sync.onRelocated(there, 0.4, moved = true, peek = true)
        sync.flushNow()

        sync.onReturned(here, 0.11)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertThat(store.get(ReadFile)!!.cfi).isEqualTo(here)
        assertThat(outbox.log).hasSize(3)
        assertThat(outbox.positions.single().cfi).isEqualTo(here)
    }

    @Test
    fun `a look elsewhere from the place the book opened at is not sent on leaving when nothing moved`() = runTest {
        val sync = sync()

        sync.onRelocated(there, 0.4, moved = false, peek = true)
        sync.flushNow()

        assertThat(outbox.log).isEmpty()
        assertThat(store.positions).isEmpty()
    }

    @Test
    fun `a move whose storing is cut short by leaving is still sent`() = runTest {
        val slow = SlowPositionStore()
        val sync = PositionSync(ReadFile, backgroundScope, repository, slow, outbox, { kicks++ }, clocks.wall)
        val turning = launch { sync.onRelocated(there, 0.4, moved = true) }
        runCurrent()

        turning.cancel()
        sync.flushNow()

        assertThat(outbox.positions.single().cfi).isEqualTo(there)
        assertThat(kicks).isEqualTo(1)
    }

    private companion object {
        val ReadFile = FileId("12")
        val OtherFile = FileId("13")
        const val Tiny = 1e-9
    }
}

/** A store whose writes never end, as one waiting for the disk when the reader is left. */
private class SlowPositionStore : PositionStore {
    override suspend fun get(fileId: FileId): LocalPosition? = null

    override suspend fun put(position: LocalPosition) = awaitCancellation()
}
