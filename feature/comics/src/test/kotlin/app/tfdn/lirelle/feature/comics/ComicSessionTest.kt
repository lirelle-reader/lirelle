package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.PositionSync
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** An outbox that keeps a reading session only once [slow] is completed: whoever writes it waits there. */
private class SlowOutbox(private val inner: InMemoryOutbox, private val slow: CompletableDeferred<Unit>) :
    Outbox by inner {
    override suspend fun enqueueSession(fileId: FileId, session: ReadingSessionReport) {
        slow.await()
        inner.enqueueSession(fileId, session)
    }
}

/** The session of an album whose writes queue behind one that is slow. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicSessionTest {
    private val clocks = TestClocks()
    private val store = InMemoryPositionStore()
    private val kept = InMemoryOutbox()
    private val slow = CompletableDeferred<Unit>()

    private fun TestScope.session(viewModel: CoroutineScope): ComicSession {
        val outbox = SlowOutbox(kept, slow)
        val lasting = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return ComicSession(
            fileId = AlbumFile,
            positions = PositionSync(AlbumFile, viewModel, FakeReaderRepository(), store, outbox, {}, clocks.wall),
            clock = SessionClockFactory(clocks.monotonic, clocks.wall).create(),
            outbox = outbox,
            kick = {},
            lasting = lasting,
        )
    }

    private fun CoroutineScope.turnTo(session: ComicSession, page: Int) =
        launch { session.relocated(page, page.toDouble() / Pages, moved = true) }

    @Test
    fun `page turns still waiting for their turn when the reader is gone are written, the last one last`() = runTest {
        val viewModel = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val session = session(viewModel)
        session.restore(fromStart = false)
        viewModel.turnTo(session, page = 2)
        clocks.advance(40.seconds)
        // The session so far is being written, slowly: every write after it waits.
        session.paused()
        viewModel.turnTo(session, page = 3)
        viewModel.turnTo(session, page = 4)
        assertThat(store.positions[AlbumFile]?.page).isEqualTo(2)

        viewModel.cancel()
        session.close()
        slow.complete(Unit)

        assertThat(store.positions[AlbumFile]?.page).isEqualTo(4)
        assertThat(kept.positions.single().page).isEqualTo(4)
        assertThat(kept.sessions.single().session.endPercent).isWithin(1e-9).of(4 * 100.0 / Pages)
    }

    @Test
    fun `a page turned after the reader was left is not written`() = runTest {
        val viewModel = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val session = session(viewModel)
        slow.complete(Unit)
        session.restore(fromStart = false)
        viewModel.turnTo(session, page = 2)

        session.close()
        viewModel.turnTo(session, page = 3)

        assertThat(store.positions[AlbumFile]?.page).isEqualTo(2)
        viewModel.cancel()
    }
}
