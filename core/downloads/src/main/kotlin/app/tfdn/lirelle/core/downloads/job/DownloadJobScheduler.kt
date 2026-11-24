package app.tfdn.lirelle.core.downloads.job

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import app.tfdn.lirelle.core.downloads.engine.fetchesOnAnyNetwork
import app.tfdn.lirelle.core.downloads.engine.isPending
import app.tfdn.lirelle.core.downloads.engine.isUpdatePending
import app.tfdn.lirelle.core.downloads.engine.queueState
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The one job of the downloads. */
internal const val DownloadJobId = 4_001

private const val Tag = "DownloadJob"

/**
 * The job the queue needs.
 *
 * @property unmeteredOnly false when the setting, or one of the downloads or updates still to fetch, allows a
 *   metered network.
 * @property estimatedBytes what is left to fetch of the sizes known; null when nothing is known.
 */
internal data class JobRequest(val unmeteredOnly: Boolean, val estimatedBytes: Long?)

/**
 * The job [books] need: none when nothing is still to fetch, download or update (what failed waits for the
 * user).
 */
internal fun jobRequest(books: List<DownloadedBook>, allowMetered: Boolean): JobRequest? {
    val pending = books.flatMap { it.formats }.filter { it.isPending || it.isUpdatePending }
    if (pending.isEmpty()) return null
    return JobRequest(
        unmeteredOnly = !allowMetered && pending.none { it.fetchesOnAnyNetwork },
        estimatedBytes = queueState(books).leftBytes?.takeIf { it > 0 },
    )
}

/**
 * A transfer the user asked for: it has no time limit, and is kept across restarts of the device. The network
 * is one of the system's own types, not a request built here: a new request refuses a VPN, which is the default
 * network of a device behind one, and the job would then wait for ever.
 */
internal fun JobRequest.jobInfo(context: Context): JobInfo =
    JobInfo.Builder(DownloadJobId, ComponentName(context, DownloadJobService::class.java))
        .setUserInitiated(true)
        .setRequiredNetworkType(if (unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
        .setEstimatedNetworkBytes(estimatedBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0)
        .setPersisted(true)
        .build()

private val JobInfo.unmeteredOnly: Boolean?
    get() = requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

/** A job kept from a version that built its own request: behind a VPN it never runs, so it is scheduled again. */
private val JobInfo.refusesVpn: Boolean
    get() = requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

/**
 * Keeps one job with the system for as long as something is to fetch, so that the downloads go on once the
 * application is left or killed. The job is only scheduled again when the network it may use changes: scheduling
 * it stops the one that runs, so neither progress nor more downloads touch it, nor does a start of the process
 * (which may be the job's own).
 *
 * No job is kept while the profile's session waits for a sign-in: the system would run it at once — the device
 * has a network — and nothing can be fetched; it is scheduled again when somebody has signed in.
 *
 * @param waitsForSignIn whether the profile's session waits for a sign-in, as it changes.
 */
class DownloadJobScheduler internal constructor(
    private val context: Context,
    private val registry: DownloadRegistry,
    private val settings: DownloadSettingsStore,
    private val waitsForSignIn: StateFlow<Boolean>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        registry: DownloadRegistry,
        settings: DownloadSettingsStore,
        network: ProfileNetwork,
    ) : this(context, registry, settings, network.waitsForSignIn)

    /** Follows the queue, the setting and the session for as long as [scope] lives. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(registry.books, settings.settings, waitsForSignIn) { books, settings, waits ->
            if (waits) null else jobRequest(books, settings.allowMetered)
        }.collect(::keep)
    }

    /**
     * Set once the profile closes. The queue is still followed until the profile's scope ends, which is after
     * every closer has run: what it says meanwhile must not ask again for the job just taken away. Read and
     * written under this object's lock, with the scheduling it guards, so that none slips in between.
     */
    private var closed = false

    /**
     * Takes the job away, running or not, and for good: the profile closes, and nothing is fetched for it any
     * more, whatever the queue says from here on.
     */
    fun cancel() {
        synchronized(this) {
            closed = true
            context.getSystemService(JobScheduler::class.java)?.cancel(DownloadJobId)
        }
    }

    private fun keep(request: JobRequest?): Unit = synchronized(this) {
        if (closed) return
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        val scheduled = jobs.getPendingJob(DownloadJobId)
        when {
            request == null -> if (scheduled != null) jobs.cancel(DownloadJobId)
            scheduled?.unmeteredOnly != request.unmeteredOnly || scheduled.refusesVpn -> jobs.schedule(request)
        }
    }

    /** The system refuses such a job from an application it does not see as in front: the next change asks again. */
    private fun JobScheduler.schedule(request: JobRequest) {
        val accepted = try {
            schedule(request.jobInfo(context)) == JobScheduler.RESULT_SUCCESS
        } catch (refused: SecurityException) {
            Log.w(Tag, "The download job was refused", refused)
            return
        }
        if (!accepted) Log.w(Tag, "The download job was not scheduled")
    }
}
