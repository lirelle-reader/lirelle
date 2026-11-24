package app.tfdn.lirelle.core.downloads.job

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tfdn.lirelle.core.downloads.di.DownloadsProfileEntry
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.openEntryOrNull
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Download now, on the notification of a queue that waits for Wi-Fi: what is queued may use any network, as
 * with the button of the Downloads destination.
 *
 * The queue is the open profile's, asked when the button is pressed, and only when the notification was that
 * profile's ([sentByProfile]). With no profile open, or another than the one the notification was posted for
 * (it closed, and another account signed in), the button does nothing.
 */
@AndroidEntryPoint
class DownloadNowReceiver : BroadcastReceiver() {

    @Inject
    lateinit var profile: CurrentProfile

    override fun onReceive(context: Context, intent: Intent?) {
        val open = profile.openEntryOrNull<DownloadsProfileEntry>() ?: return
        if (!sentByProfile(intent, open.profileId)) return
        val downloads = open.entry.downloadsQueue()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                downloads.allowAnyNetworkNow()
            } finally {
                pending.finish()
            }
        }
    }
}
