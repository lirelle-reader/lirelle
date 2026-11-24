package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.engine.DownloadRunner
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the downloads do without a screen, started with the profile: the queue runs for as long as the
 * profile is open, so that a download asked starts at once; the system's job takes over when it does not; a
 * queue that waits for Wi-Fi, and one that is done, say so in a notification.
 */
class BackgroundDownloads @Inject constructor(
    private val runner: DownloadRunner,
    private val scheduler: DownloadJobScheduler,
    private val notices: QueueNotices,
    private val downloaded: RegistryDownloadedFiles,
) {
    fun start(scope: CoroutineScope) {
        scheduler.start(scope)
        // Known before anything is played: the player finds a downloaded audio file without waiting.
        downloaded.start(scope)
        // The job runs the same runner, one run at a time: no file is fetched twice.
        scope.launch { runner.run() }
        scope.launch { notices.run() }
    }
}
