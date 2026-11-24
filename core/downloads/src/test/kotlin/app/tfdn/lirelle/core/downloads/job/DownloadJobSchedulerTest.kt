package app.tfdn.lirelle.core.downloads.job

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DownloadJobSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val jobs = checkNotNull(context.getSystemService(JobScheduler::class.java))
    private val registry = MemoryRegistry()
    private val settings = MemorySettings()

    /** Whether the profile's session waits for a sign-in. */
    private val waitsForSignIn = MutableStateFlow(false)

    private fun schedulerTest(test: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)
        test()
    }

    private suspend fun put(bookId: String, state: FormatState?, anyNetwork: Boolean = false) =
        registry.update(BookId(bookId)) { state?.let { book(bookId, record(it, anyNetwork = anyNetwork)) } }

    private val JobInfo.unmetered: Boolean
        get() = checkNotNull(requiredNetwork).hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    @Test
    fun `nothing queued schedules nothing`() = schedulerTest {
        assertThat(jobs.allPendingJobs).isEmpty()
    }

    @Test
    fun `a queued download schedules one job the user asked for, kept across restarts, off metered networks`() =
        schedulerTest {
            put("1", FormatState.Queued)

            val job = jobs.allPendingJobs.single()
            assertThat(job.id).isEqualTo(DownloadJobId)
            assertThat(job.service.className).isEqualTo(DownloadJobService::class.java.name)
            assertThat(job.isUserInitiated).isTrue()
            assertThat(job.isPersisted).isTrue()
            assertThat(checkNotNull(job.requiredNetwork).hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .isTrue()
            assertThat(job.unmetered).isTrue()
            assertThat(job.estimatedNetworkDownloadBytes).isEqualTo(JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
        }

    /** Behind a VPN the default network is the VPN: a job that refuses it waits for ever. */
    @Test
    fun `the job also runs behind a VPN, on the networks the system's own job types take`() = schedulerTest {
        put("1", FormatState.Queued)
        val unmeteredOnly = checkNotNull(jobs.allPendingJobs.single().requiredNetwork)
        settings.update { it.copy(allowMetered = true) }
        val anyNetwork = checkNotNull(jobs.allPendingJobs.single().requiredNetwork)

        listOf(unmeteredOnly, anyNetwork).forEach { network ->
            assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).isFalse()
            assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)).isFalse()
            assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
            assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isTrue()
        }
        assertThat(unmeteredOnly.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
        assertThat(anyNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isFalse()
        assertThat(jobs.allPendingJobs.single().isUserInitiated).isTrue()
    }

    /** A job kept from a version that refused VPNs (it is persisted) would wait for ever: it is replaced. */
    @Test
    fun `a job scheduled before that refuses a VPN is scheduled again`() = runTest(UnconfinedTestDispatcher()) {
        val old = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        val service = ComponentName(context, DownloadJobService::class.java)
        jobs.schedule(JobInfo.Builder(DownloadJobId, service).setRequiredNetwork(old).build())
        put("1", FormatState.Queued)

        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)

        val network = checkNotNull(jobs.allPendingJobs.single().requiredNetwork)
        assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).isFalse()
        assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
    }

    @Test
    fun `the job says how much is left when it is scheduled`() = schedulerTest {
        put("1", FormatState.Downloading(100, 400))

        assertThat(jobs.allPendingJobs.single().estimatedNetworkDownloadBytes).isEqualTo(300)
    }

    @Test
    fun `with the setting the job may use any network`() = schedulerTest {
        settings.update { it.copy(allowMetered = true) }
        put("1", FormatState.Queued)

        assertThat(jobs.allPendingJobs.single().unmetered).isFalse()
    }

    @Test
    fun `a download allowed on any network lets the job use any network`() = schedulerTest {
        put("1", FormatState.Queued, anyNetwork = true)

        assertThat(jobs.allPendingJobs.single().unmetered).isFalse()
    }

    @Test
    fun `the job is scheduled again when the setting flips, and there is still one`() = schedulerTest {
        put("1", FormatState.Queued)

        settings.update { it.copy(allowMetered = true) }
        assertThat(jobs.allPendingJobs.single().unmetered).isFalse()

        settings.update { it.copy(allowMetered = false) }
        assertThat(jobs.allPendingJobs.single().unmetered).isTrue()
    }

    @Test
    fun `more downloads and their progress leave the job as it is`() = schedulerTest {
        put("1", FormatState.Queued)
        val job = jobs.allPendingJobs.single()

        put("1", FormatState.Downloading(100, 400))
        put("2", FormatState.Queued)

        assertThat(jobs.allPendingJobs.single()).isSameInstanceAs(job)
    }

    @Test
    fun `the job is cancelled when nothing is left to fetch`() = schedulerTest {
        put("1", FormatState.Queued)

        put("1", FormatState.Complete(400))

        assertThat(jobs.allPendingJobs).isEmpty()
    }

    /** The device has a network: the system would run the job at once, and again, to fetch nothing. */
    @Test
    fun `no job is kept while the session waits for a sign-in, and it comes back once somebody signed in`() =
        schedulerTest {
            put("1", FormatState.Queued)
            assertThat(jobs.allPendingJobs).hasSize(1)

            waitsForSignIn.value = true
            assertThat(jobs.allPendingJobs).isEmpty()
            // What is asked meanwhile waits with the rest.
            put("2", FormatState.Queued)
            assertThat(jobs.allPendingJobs).isEmpty()

            waitsForSignIn.value = false
            assertThat(jobs.allPendingJobs.single().id).isEqualTo(DownloadJobId)
        }

    @Test
    fun `a profile that starts waiting for a sign-in takes away the job kept from before`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        put("1", FormatState.Queued)
        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)
        assertThat(jobs.allPendingJobs).hasSize(1)
        waitsForSignIn.value = true

        // As a process that starts with the profile marked, the job still kept by the system.
        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)

        assertThat(jobs.allPendingJobs).isEmpty()
    }

    @Test
    fun `a job kept from before the process started is left as it is`() = runTest(UnconfinedTestDispatcher()) {
        put("1", FormatState.Queued)
        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)
        val job = jobs.allPendingJobs.single()

        DownloadJobScheduler(context, registry, settings, waitsForSignIn).start(backgroundScope)

        assertThat(jobs.allPendingJobs.single()).isSameInstanceAs(job)
    }

    @Test
    fun `cancelling takes the job away, whatever is queued`() = schedulerTest {
        put("1", FormatState.Queued)

        DownloadJobScheduler(context, registry, settings, waitsForSignIn).cancel()

        assertThat(jobs.allPendingJobs).isEmpty()
    }

    /** The queue is followed until the profile's scope ends, which is after the job was taken away at its close. */
    @Test
    fun `once the job was taken away, what the queue and the setting say afterwards schedules nothing`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val scheduler = DownloadJobScheduler(context, registry, settings, waitsForSignIn)
        scheduler.start(backgroundScope)
        put("1", FormatState.Queued)
        assertThat(jobs.allPendingJobs).hasSize(1)

        scheduler.cancel()
        put("1", FormatState.Downloading(100, 400))
        settings.update { it.copy(allowMetered = true) }

        assertThat(jobs.allPendingJobs).isEmpty()
    }
}
