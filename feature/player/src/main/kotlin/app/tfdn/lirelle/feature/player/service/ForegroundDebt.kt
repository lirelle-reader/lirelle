package app.tfdn.lirelle.feature.player.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/** How a resumption ended, for the [ForegroundDebt]. */
enum class ResumeOutcome {
    /** The book was loaded: Media3 enters the foreground with it. */
    Loaded,

    /** Rejected (no longer the last book, a store that cannot be read) or not loaded (no network). */
    Rejected,

    /** Replaced by another command (another book, closing): the safety check decides. */
    Cancelled,
}

/**
 * The foreground start a play button owes the system: the media button receiver started the service with
 * `startForegroundService`, and the service must enter the foreground within seconds or the app is ended.
 * Media3 does it when a book plays; when none will ([settle]: the last book was rejected or could not be
 * loaded, possibly at once, before Media3 ever saw it), the service enters the foreground for an instant
 * with a minimal notification and leaves it, as Media3 itself does when it stops, then stops.
 *
 * A start by a play button through [PlaybackButtonReceiver] owes, and so does a button of Media3's own
 * notification pressed with no book loaded (the notification outlived a process the system ended, and its
 * buttons start the service the same way); only while the service is not in the foreground already. With a
 * book loaded, Media3's own starts owe nothing, nor does a controller of the app bound to the service. A
 * check [SafetyDelayMs] after the start settles a debt
 * still owed with the service out of the foreground (a record cleared meanwhile, a cancelled load). Call it
 * on the main thread.
 */
class ForegroundDebt(
    private val service: Service,
    private val notification: () -> Notification,
) {
    private val main = Handler(Looper.getMainLooper())
    private var owed = false
    private val checkLater = Runnable(::check)

    private val inForeground: Boolean
        get() = service.foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE

    /**
     * The service was started with [intent]: out of the foreground, a play button's start owes, and any
     * media button's start when there is [noBook] loaded.
     */
    fun onStart(intent: Intent?, noBook: Boolean = false) {
        if (intent == null || inForeground) return
        val button = noBook && intent.action == Intent.ACTION_MEDIA_BUTTON
        if (!button && !PlaybackButtonReceiver.isPlayButtonStart(intent)) return
        owed = true
        main.removeCallbacks(checkLater)
        main.postDelayed(checkLater, SafetyDelayMs)
    }

    /** The service ends: no check may run after it. */
    fun release() {
        owed = false
        main.removeCallbacks(checkLater)
    }

    /** The service's wiring: how a resumption ends pays, forgives or leaves the debt to the check. */
    fun follow(book: BookSession) {
        book.resumed = { outcome ->
            when (outcome) {
                ResumeOutcome.Loaded -> forgive()
                ResumeOutcome.Rejected -> settle()
                ResumeOutcome.Cancelled -> Unit
            }
        }
    }

    /** A book plays: Media3 enters the foreground with its own notification. */
    fun forgive() {
        owed = false
    }

    /**
     * Nothing will play: the foreground for an instant, then the service stops. Nothing when not owed, or when
     * the service is in the foreground already (Media3 took it).
     */
    fun settle() {
        if (!owed) return
        owed = false
        if (inForeground) return
        try {
            service.startForeground(StartingId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (refused: IllegalStateException) {
            Log.w(Tag, "Could not enter the foreground owed to a play button", refused)
        }
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }

    private fun check() {
        if (owed) settle()
    }

    companion object {
        /** How long a play button's start may wait for a book before the debt is paid all the same. */
        const val SafetyDelayMs = 3_000L

        private const val Tag = "ForegroundDebt"

        /** Not Media3's notification: it can never replace or remove the one of a book that plays. */
        private const val StartingId = 1003
    }
}
