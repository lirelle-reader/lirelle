package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadJobWorkTest {
    private val books = MutableStateFlow(listOf(book("1", record(FormatState.Queued))))

    /** Whether the profile's session waits for a sign-in. */
    private var waitsForSignIn = false

    /** A run that leaves the queue as [after]. */
    private fun work(after: List<DownloadedBook>) = DownloadJobWork({ books.value = after }, books) { waitsForSignIn }

    private val open = MutableStateFlow(true)

    private suspend fun DownloadJobWork.run() = run(open)

    @Test
    fun `a run that leaves nothing asks for no other`() = runTest {
        assertThat(work(listOf(book("1", record(FormatState.Complete(400))))).run()).isFalse()
        assertThat(work(emptyList()).run()).isFalse()
    }

    @Test
    fun `a run that leaves only failures asks for no other`() = runTest {
        val after = listOf(book("1", record(FormatState.Failed(DownloadFailure.Damaged, 10))))

        assertThat(work(after).run()).isFalse()
    }

    @Test
    fun `a run that leaves downloads waiting asks for another`() = runTest {
        val after = listOf(
            book("1", record(FormatState.Complete(400))),
            book("2", record(FormatState.Waiting(WaitReason.UnmeteredOnly, 10, 400))),
        )

        assertThat(work(after).run()).isTrue()
    }

    /** The device has a network, so the system would run the job again at once, to fetch nothing. */
    @Test
    fun `a run that leaves downloads waiting for a sign-in asks for no other`() = runTest {
        val after = listOf(book("1", record(FormatState.Waiting(WaitReason.NoNetwork, 10, 400))))
        val work = work(after)

        // The session ended while the run was fetching.
        waitsForSignIn = true

        assertThat(work.run()).isFalse()
        // The downloads are still to fetch: the scheduler asks for the job again once somebody signed in.
        assertThat(work.left.first()).isTrue()
    }

    @Test
    fun `a profile that closes stops the fetching, and no other run is asked for`() = runTest {
        var stopped = false
        val fetching = DownloadJobWork(
            runUntilIdle = {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            },
            books = books,
            waitsForSignIn = { false },
        )
        val run = async { fetching.run(open) }
        runCurrent()
        assertThat(run.isCompleted).isFalse()

        open.value = false

        // Downloads are left, and would ask for another run: not for a profile that closed.
        assertThat(run.await()).isFalse()
        assertThat(stopped).isTrue()
    }

    @Test
    fun `a profile already closed when the job starts fetches nothing to its end`() = runTest {
        open.value = false
        val fetching = DownloadJobWork({ awaitCancellation() }, books) { false }

        assertThat(fetching.run(open)).isFalse()
    }

    @Test
    fun `work is left for as long as something is still to fetch`() = runTest {
        val work = work(emptyList())

        assertThat(work.left.first()).isTrue()
        work.run()
        assertThat(work.left.first()).isFalse()
    }
}
