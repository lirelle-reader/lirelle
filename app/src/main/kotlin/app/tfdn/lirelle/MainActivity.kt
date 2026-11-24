package app.tfdn.lirelle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.session.profile.OpenProfiles
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import app.tfdn.lirelle.navigation.DownloadsRequests
import app.tfdn.lirelle.navigation.ReaderRequests
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var oidcRedirects: OidcRedirectRelay

    @Inject
    lateinit var profiles: OpenProfiles

    /**
     * The reader a narration's notification asked for, until the signed-in screens open it: only while the
     * profile that notification was posted for is the open one.
     */
    internal val readerRequests = ReaderRequests { profiles.current.value?.profileId }

    /**
     * The Downloads destination a notification of the downloads asked for, until the shell selects it: only
     * while the profile that notification was posted for is the open one.
     */
    internal val downloadsRequests = DownloadsRequests { profiles.current.value?.profileId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after a configuration change the intent is the old one.
        if (savedInstanceState == null) deliver(intent)
        setContent {
            LirelleTheme { LirelleApp(readerRequests, downloadsRequests) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliver(intent)
    }

    /**
     * What [intent] carries for the screens: an OIDC redirect, a reader or the Downloads destination asked
     * for, each with the profile whose notification asked. Nothing here changes profile: a request of another
     * profile than the open one is dropped where it is taken.
     */
    private fun deliver(intent: Intent?) {
        oidcRedirectOf(intent)?.let(oidcRedirects::deliver)
        val profileId = intent?.getStringExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID)
        takeReaderRequest(intent)?.let { readerRequests.open(it, profileId) }
        val downloadsOf = intent?.getStringExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID)
        if (takeDownloadsRequest(intent)) downloadsRequests.open(downloadsOf)
    }
}

/** The OIDC redirect URI carried by [intent], or null when it is not one to act on. */
internal fun oidcRedirectOf(intent: Intent?): String? {
    val data = intent?.data ?: return null
    // An activity brought back from the recent apps is handed its original intent again:
    // that redirect was already used.
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    return data.toString().takeIf { data.scheme == OidcScheme && data.host == OidcHost }
}

/**
 * The book whose reader [intent] asks for (a tap on a narration's notification), taken out of it: an activity
 * made again with that intent does not open the reader again. Null when it asks for none, and for an intent
 * replayed from the recent apps: that request was already answered.
 */
internal fun takeReaderRequest(intent: Intent?): BookId? {
    val key = PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID
    if (intent?.hasExtra(key) != true) return null
    // An intent holds no id: its text.
    val bookId = intent.getStringExtra(key)
    intent.removeExtra(key)
    val replayed = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    return bookId?.takeUnless { replayed || it.isEmpty() }?.let(::BookId)
}

/**
 * Whether [intent] asks for the Downloads destination (a tap on a notification of the downloads), the request
 * taken out of it, with the profile it names: an activity made again with that intent does not select the
 * destination again. False for an intent replayed from the recent apps: that request was already answered.
 */
internal fun takeDownloadsRequest(intent: Intent?): Boolean {
    val key = DownloadsIntents.EXTRA_OPEN_DOWNLOADS
    if (intent?.hasExtra(key) != true) return false
    val asked = intent.getBooleanExtra(key, false)
    intent.removeExtra(key)
    intent.removeExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID)
    return asked && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
}

private const val OidcScheme = "bookorbit"
private const val OidcHost = "oauth2-callback"
