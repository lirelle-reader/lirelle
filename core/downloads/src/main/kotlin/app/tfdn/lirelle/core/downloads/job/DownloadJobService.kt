package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import app.tfdn.lirelle.core.downloads.di.DownloadsProfileEntry
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.OpenEntry
import app.tfdn.lirelle.core.session.profile.openEntryOrNull
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The downloads, as a transfer the user asked for: the system runs it while the network it needs is there, with
 * no time limit, the application in front or not, and shows its notification for as long as it runs.
 *
 * The system starts it when it sees fit, in a process of its own making if need be. It fetches for the profile
 * open at that moment and for no other: its queue and its notices are asked of that profile when the job
 * starts, not injected, and with no profile open there is nothing to do. It keeps them for as long as it runs
 * ([OpenEntry]), so it lets go of them when that profile closes: the fetching stops, and the job ends without
 * asking to be run again.
 */
@AndroidEntryPoint
class DownloadJobService : JobService() {

    @Inject
    lateinit var profile: CurrentProfile

    private var scope: CoroutineScope? = null

    /** The profile the running job fetches for. */
    private var taken: OpenEntry<DownloadsProfileEntry>? = null

    /** Whether something is still to fetch, for a stop that comes from the system. */
    private var left = true

    override fun onStartJob(params: JobParameters): Boolean {
        // A job kept across a sign-out or a restart, and nobody signed in: done, the next profile asks its own.
        val open = profile.openEntryOrNull<DownloadsProfileEntry>() ?: return false
        taken = open
        val work = open.entry.downloadJobWork()
        val notices = open.entry.downloadNotices()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { this.scope = it }
        // At once: such a job is stopped when it shows nothing.
        show(params, open.entry.downloadNotifications().waiting(null))
        scope.launch { notices.progress.collect { show(params, it) } }
        scope.launch { work.left.collect { left = it } }
        scope.launch {
            // Until nothing can be fetched, or until the profile closes: no other run is asked for then, nor
            // while its session waits for a sign-in.
            val again = withContext(Dispatchers.Default) { work.run(open.open) }
            jobFinished(params, again && open.isOpen)
            scope.cancel()
        }
        return true
    }

    /**
     * The network was lost, or the system has other plans: what was received is kept, the job comes back. A
     * profile that closes takes the job away too: nothing is asked back for it, whether the system or this
     * service saw it first.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        scope = null
        return left && taken?.isOpen == true
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private fun show(params: JobParameters, notification: Notification) =
        setNotification(params, ProgressNotificationId, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
}
