package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The position of a file read by pages, a comic: its page goes along the whole chain, as the server counts it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagePositionSyncTest {

    private val clocks = TestClocks()
    private val t0 = clocks.now
    private val repository = FakeReaderRepository()
    private val store = InMemoryPositionStore()
    private val outbox = InMemoryOutbox()
    private var kicks = 0

    private fun TestScope.sync() = PositionSync(
        fileId = Album,
        scope = backgroundScope,
        repository = repository,
        store = store,
        outbox = outbox,
        kick = { kicks++ },
        wall = clocks.wall,
    )

    private fun TestScope.flusher() =
        OutboxFlusher(outbox, repository, backgroundScope, clocks.wall, ReadingChanges())

    /** The server holds [page] of an album of [Pages] pages. */
    private fun serverHas(page: Int, secondsFromStart: Long) {
        val at = t0.plusSeconds(secondsFromStart)
        repository.positionResult =
            ApiResult.Success(ReadingPosition(Album, null, percentOf(page), updatedAt = at, page = page))
    }

    private fun local(page: Int, secondsFromStart: Long = 0) =
        LocalPosition(Album, cfi = null, percent = percentOf(page), at = t0.plusSeconds(secondsFromStart), page = page)

    @Test
    fun `a page turned is kept at once and queued once the page stayed still`() = runTest {
        val sync = sync()

        sync.onRelocated(cfi = null, fraction = 0.25, moved = true, page = 75)

        val turned = LocalPosition(Album, cfi = null, percent = 25.0, at = t0, page = 75)
        assertThat(store.get(Album)).isEqualTo(turned)
        assertThat(outbox.positions).isEmpty()

        advanceTimeBy(3.seconds)

        assertThat(outbox.positions).containsExactly(turned)
        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `the place a comic opened at is neither kept nor sent`() = runTest {
        sync().onRelocated(cfi = null, fraction = 0.25, moved = false, page = 75)
        advanceTimeBy(3.seconds)

        assertThat(store.positions).isEmpty()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a queued page reaches the server`() = runTest {
        outbox.enqueuePosition(local(page = 75))

        flusher().kick()
        runCurrent()

        assertThat(repository.savedPositions)
            .containsExactly(ReadingPosition(Album, cfi = null, percent = 25.0, updatedAt = null, page = 75))
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a position without a page is sent without one`() = runTest {
        outbox.enqueuePosition(LocalPosition(Album, "epubcfi(/6/4!/4/2/1:0)", 12.5, t0))

        flusher().kick()
        runCurrent()

        assertThat(repository.savedPositions.single().page).isNull()
    }

    @Test
    fun `restore gives the server's page and keeps it on the device`() = runTest {
        serverHas(page = 75, secondsFromStart = -3600)

        val restored = sync().restore()

        assertThat(restored).isEqualTo(RestoredPosition(cfi = null, percent = 25.0, page = 75))
        assertThat(store.get(Album)).isEqualTo(local(page = 75, secondsFromStart = -3600))
    }

    @Test
    fun `restore gives the device's page when the server cannot be reached`() = runTest {
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        store.put(local(page = 75, secondsFromStart = -3600))

        assertThat(sync().restore()).isEqualTo(RestoredPosition(cfi = null, percent = 25.0, page = 75))
    }

    @Test
    fun `a comic read again opens without a page`() = runTest {
        serverHas(page = 75, secondsFromStart = -3600)

        assertThat(sync().restore(fromStart = true)).isEqualTo(RestoredPosition(cfi = null, percent = 0.0))
    }

    @Test
    fun `going to the page of another device keeps that page`() = runTest {
        serverHas(page = 75, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        serverHas(page = 90, secondsFromStart = 60)
        val offered = checkNotNull(sync.newerOnServer())

        sync.accept(offered)

        assertThat(store.get(Album)).isEqualTo(local(page = 90, secondsFromStart = 60))
    }

    @Test
    fun `the next page read on another device is offered, less than half a percent away`() = runTest {
        serverHas(page = 150, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()

        serverHas(page = 151, secondsFromStart = 60)

        assertThat(percentOf(151) - percentOf(150)).isLessThan(0.5)
        assertThat(sync.newerOnServer()?.page).isEqualTo(151)
    }

    @Test
    fun `the same page written again elsewhere is not offered`() = runTest {
        serverHas(page = 150, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()

        serverHas(page = 150, secondsFromStart = 60)

        assertThat(sync.newerOnServer()).isNull()
    }

    @Test
    fun `our own page echoed back is not offered once we turned further`() = runTest {
        serverHas(page = 150, secondsFromStart = -3600)
        val sync = sync()
        sync.restore()
        sync.onRelocated(cfi = null, fraction = percentOf(151) / 100, moved = true, page = 151)
        sync.onRelocated(cfi = null, fraction = percentOf(153) / 100, moved = true, page = 153)

        // The server's clock is ahead of the device's.
        serverHas(page = 151, secondsFromStart = 300)
        assertThat(sync.newerOnServer()).isNull()

        // Another device, one page before our first write.
        serverHas(page = 152, secondsFromStart = 300)
        assertThat(sync.newerOnServer()?.page).isEqualTo(152)
    }

    private companion object {
        val Album = FileId("31")
        const val Pages = 300

        fun percentOf(page: Int): Double = page * 100.0 / Pages
    }
}
