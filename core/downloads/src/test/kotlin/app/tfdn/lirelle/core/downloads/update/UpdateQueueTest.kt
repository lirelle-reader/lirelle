package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.engine.Mobile
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("1")
private val OtherBook = BookId("2")

/** An update is a download like another: the queue, the Wi-Fi rule, the space, cancel and retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 keeps an audiobook of two files; the server then has another version of the second. */
    private suspend fun QueueFixture.audiobookChanged() {
        serve(TheBook, Audiobook, audio("a1"), audio("a2"))
        queue.ask(TheBook, setOf(Audiobook))
        runner.runUntilIdle()
        serve(TheBook, Audiobook, audio("a1"), audio("a2", bytes = 70, version = "v2"), version = "v2")
    }

    /** The fetch of the changed file receives ten bytes, then goes on until it is stopped. */
    private fun QueueFixture.fetchStalls() {
        val part = files.updates.part(TheBook, Audiobook, "a2")
        updateFetcher.on("a2") { fetch ->
            checkNotNull(part.parentFile).mkdirs()
            part.writeBytes(ByteArray(10))
            fetch.onProgress(10)
            awaitCancellation()
        }
    }

    private suspend fun QueueFixture.assertKeptCopyUntouched() {
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(200))
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v1")
        assertThat(file(TheBook, Audiobook, "a2").length()).isEqualTo(100)
    }

    @Test
    fun `an update being fetched is the queue's active book, marked as an update`() = queueTest {
        audiobookChanged()
        fetchStalls()
        askUpdate(TheBook)
        keepRunning()
        it.runCurrent()

        val state = queue.queue.first()

        assertThat(state.active).isEqualTo(QueueEntry(TheBook, listOf(Audiobook), 10, 70, null, update = true))
        assertThat(state.leftBytes).isEqualTo(60)
        assertKeptCopyUntouched()
    }

    @Test
    fun `cancelling an update drops what it fetched and leaves it waiting for the user`() = queueTest {
        audiobookChanged()
        fetchStalls()
        askUpdate(TheBook)
        keepRunning()
        it.runCurrent()
        assertThat(besideNames(TheBook)).isNotEmpty()

        queue.cancel(TheBook, Audiobook)
        it.runCurrent()

        val update = checkNotNull(update(TheBook, Audiobook))
        assertThat(update.state).isNull()
        assertThat(update.version).isEqualTo("v2")
        assertThat(besideNames(TheBook)).isEmpty()
        assertThat(queue.queue.first().active).isNull()
        assertKeptCopyUntouched()
    }

    @Test
    fun `a failed update says why, leaves the kept copy, and retry queues it again`() = queueTest {
        audiobookChanged()
        val failure = DownloadFailure.Server(ApiError.ServerFailed)
        updateFetcher.on("a2") { FetchResult.Refused(failure) }
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(update(TheBook, Audiobook)?.state).isEqualTo(FormatState.Failed(failure, 0))
        assertThat(queue.queue.first().waiting)
            .containsExactly(QueueEntry(TheBook, listOf(Audiobook), 0, null, failure, update = true))
        assertKeptCopyUntouched()

        queue.retry(TheBook)
        runner.runUntilIdle()

        assertThat(record(TheBook, Audiobook).version).isEqualTo("v2")
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(170))
    }

    @Test
    fun `without room for it an update fails with what is missing, and the kept copy stays`() = queueTest {
        audiobookChanged()
        freeBytes = 10
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(update(TheBook, Audiobook)?.state).isEqualTo(FormatState.Failed(DownloadFailure.NoSpace(60), 0))
        assertKeptCopyUntouched()
    }

    @Test
    fun `an update waits for Wi-Fi as a download does, and the user's word lets it go`() = queueTest {
        audiobookChanged()
        network.value = Mobile
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(update(TheBook, Audiobook)?.state).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 70))
        val state = queue.queue.first()
        assertThat(state.waitReason).isEqualTo(WaitReason.UnmeteredOnly)
        assertThat(state.leftBytes).isEqualTo(70)
        assertThat(state.waiting.map { entry -> entry.update }).containsExactly(true)

        queue.allowAnyNetworkNow()
        runner.runUntilIdle()

        assertThat(record(TheBook, Audiobook).version).isEqualTo("v2")
    }

    @Test
    fun `an update asked later follows the Wi-Fi rule again`() = queueTest {
        audiobookChanged()
        network.value = Mobile
        askUpdate(TheBook)
        queue.allowAnyNetworkNow()
        queue.cancel(TheBook)

        askUpdate(TheBook)
        runner.runUntilIdle()

        assertThat(update(TheBook, Audiobook)?.state).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 70))
    }

    @Test
    fun `when the account loses the permission an update in progress is cancelled and the kept copy stays`() =
        queueTest {
            audiobookChanged()
            fetchStalls()
            askUpdate(TheBook)
            keepRunning()
            it.runCurrent()

            canDownload.value = false
            it.runCurrent()

            assertThat(checkNotNull(update(TheBook, Audiobook)).state).isNull()
            assertThat(besideNames(TheBook)).isEmpty()
            assertKeptCopyUntouched()
        }

    @Test
    fun `an update goes behind what was asked before it`() = queueTest {
        audiobookChanged()
        network.value = Mobile
        it.advanceTimeBy(1_000)
        serveBook(OtherBook)
        queue.ask(OtherBook, setOf(Audiobook))
        it.advanceTimeBy(1_000)
        askUpdate(TheBook)
        runner.runUntilIdle()

        assertThat(queue.queue.first().waiting.map { entry -> entry.bookId })
            .containsExactly(OtherBook, TheBook).inOrder()
    }
}
