package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.WaitReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A complete audiobook, once fetched on a metered network, whose update of 300 bytes is in [state]: null while it
 * waits for the user.
 */
private fun updating(state: FormatState?, anyNetwork: Boolean = false) =
    record(FormatState.Complete(10), anyNetwork = true)
        .copy(update = FormatUpdate("v2", emptyList(), bytes = 300, state = state, anyNetwork = anyNetwork))

class JobRequestTest {

    @Test
    fun `nothing kept or asked needs no job`() {
        assertThat(jobRequest(emptyList(), allowMetered = false)).isNull()
    }

    @Test
    fun `what is complete, failed or being deleted needs no job`() {
        val books = listOf(
            book("1", record(FormatState.Complete(10))),
            book("2", record(FormatState.Failed(DownloadFailure.Damaged, 4))),
            book("3", record(FormatState.Queued, removing = true)),
        )

        assertThat(jobRequest(books, allowMetered = true)).isNull()
    }

    @Test
    fun `a queued download needs a network that is not metered`() {
        val request = jobRequest(listOf(book("1", record(FormatState.Queued))), allowMetered = false)

        assertThat(request).isEqualTo(JobRequest(unmeteredOnly = true, estimatedBytes = null))
    }

    @Test
    fun `the setting lets the job use any network`() {
        val request = jobRequest(listOf(book("1", record(FormatState.Queued))), allowMetered = true)

        assertThat(request?.unmeteredOnly).isFalse()
    }

    @Test
    fun `a download allowed on any network lets the job use any network`() {
        val books = listOf(
            book("1", record(FormatState.Queued)),
            book("2", record(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null), anyNetwork = true)),
        )

        assertThat(jobRequest(books, allowMetered = false)?.unmeteredOnly).isFalse()
    }

    @Test
    fun `a complete download allowed on any network no longer counts`() {
        val books = listOf(
            book("1", record(FormatState.Queued)),
            book("2", record(FormatState.Complete(10), anyNetwork = true)),
        )

        assertThat(jobRequest(books, allowMetered = false)?.unmeteredOnly).isTrue()
    }

    @Test
    fun `the bytes left of the sizes known are the estimate`() {
        val books = listOf(
            book(
                "1",
                record(FormatState.Downloading(100, 400), DownloadFormat.Text),
                record(FormatState.Waiting(WaitReason.NoNetwork, 50, 250), DownloadFormat.Audiobook),
                record(FormatState.Queued, DownloadFormat.Narration),
            ),
        )

        assertThat(jobRequest(books, allowMetered = false)?.estimatedBytes).isEqualTo(500)
    }

    @Test
    fun `nothing left of the sizes known is no estimate`() {
        val books = listOf(book("1", record(FormatState.Downloading(400, 400))))

        assertThat(jobRequest(books, allowMetered = false)?.estimatedBytes).isNull()
    }

    @Test
    fun `an update asked needs the job as a download does, what it will fetch being the estimate`() {
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 300)

        val request = jobRequest(listOf(book("1", updating(waiting))), allowMetered = false)

        assertThat(request).isEqualTo(JobRequest(unmeteredOnly = true, estimatedBytes = 300))
    }

    @Test
    fun `an update allowed on any network lets the job use any network`() {
        val books = listOf(book("1", updating(FormatState.Queued, anyNetwork = true)))

        assertThat(jobRequest(books, allowMetered = false)?.unmeteredOnly).isFalse()
    }

    @Test
    fun `an update that waits for the user, failed, or is fetched whole needs no job`() {
        val books = listOf(
            book("1", updating(null)),
            book("2", updating(FormatState.Failed(DownloadFailure.Damaged, 4))),
            book("3", updating(FormatState.Complete(300))),
        )

        assertThat(jobRequest(books, allowMetered = true)).isNull()
    }
}
