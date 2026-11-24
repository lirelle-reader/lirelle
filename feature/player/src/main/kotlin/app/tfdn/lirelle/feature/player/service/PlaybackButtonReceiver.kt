package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import app.tfdn.lirelle.core.session.profile.entryOrNull
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.player.di.PlayerReceiverEntry
import dagger.hilt.android.EntryPointAccessors

/**
 * Media3's media button receiver, which starts the player service in the foreground for a play button
 * pressed while it is not running, but only when there is a last book to play: the service could not reach
 * the foreground in time otherwise (nobody signed in, nothing listened to yet), and the system would end
 * the app. The last book is the open profile's, so its presence means the account signed in now; with no
 * profile open there is none, and nothing starts.
 */
@OptIn(UnstableApi::class)
class PlaybackButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        // Asked at the moment of acting, and not kept: the profile may be another, or none, the next time.
        val device = EntryPointAccessors.fromApplication(context, PlayerReceiverEntry::class.java)
        if (!shouldStart(device.currentProfile().entryOrNull<PlayerProfileEntry>()?.lastBookMark())) return false
        // Media3's own starts (its notification buttons) carry the same action: this one owes the foreground.
        markPlayButtonStart(intent)
        return true
    }

    companion object {
        private const val PlayButtonStart = "app.tfdn.lirelle.player.PLAY_BUTTON_START"

        /** @param mark the open profile's; null when no profile is open. */
        fun shouldStart(mark: LastBookMark?): Boolean = mark?.exists() == true

        /** Whether the service was started by this receiver, for a play button. */
        fun isPlayButtonStart(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MEDIA_BUTTON && intent.getBooleanExtra(PlayButtonStart, false)

        /** Marks [intent] as this receiver does. */
        fun markPlayButtonStart(intent: Intent): Intent = intent.putExtra(PlayButtonStart, true)
    }
}
