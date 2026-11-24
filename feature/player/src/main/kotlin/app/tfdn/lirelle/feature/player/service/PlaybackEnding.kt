package app.tfdn.lirelle.feature.player.service

import android.util.Log
import app.tfdn.lirelle.core.session.kindAndFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val Tag = "PlaybackService"

/**
 * How long a profile that closes waits for its playback to have ended. Less than what a closer is given: what
 * the closer still has to do after the wait is done even when the service did not end.
 */
internal const val PlaybackEndWaitMillis = 3_000L

/**
 * Ends playback for a profile that closes, and waits until it has ended: the book's last position and its
 * listening are written into the profile's stores, and the service reads the profile's files; what comes
 * next throws those objects away, and may delete those files.
 *
 * The app's own connection is let go first: a service something is still bound to does not end. Then the
 * service closes its book, which returns once the last writes are made, and is waited for until it no longer
 * runs.
 *
 * @param releaseController lets go of the session the profile's screens hold, for good.
 * @param running whether a playback service of that profile runs, its player not yet released.
 * @return false when playback had not ended after [PlaybackEndWaitMillis]: the closing goes on without it,
 *   and the service ends by itself when it finds its profile closed.
 */
internal suspend fun endPlayback(
    shutdown: PlaybackShutdown,
    running: StateFlow<Boolean>,
    releaseController: () -> Unit,
): Boolean {
    releaseController()
    val ended = withTimeoutOrNull(PlaybackEndWaitMillis) {
        shutdown.stopPlayback()
        running.first { !it }
    }
    return ended != null
}

/**
 * Lets go of what the player reads through — the profile's audio cache, which locks its folder — once nothing
 * plays any more, never under a player still reading: at once when playback [ended] in time, otherwise when
 * the service does end, which it does by itself on finding its profile closed. The closing does not wait for
 * that: the profile's other objects go meanwhile, and only the lock is held a little longer.
 *
 * A release that fails is told and stops nothing: the late one runs where nobody would catch it, and what is
 * not caught there ends the app.
 *
 * @param running whether the service of the profile that closes still has its player: that profile's own
 *   word ([PlaybackShutdown.reading]). One flag for the process would also be held by the service the next
 *   profile starts, and the cache of this one kept until that other service ends.
 * @param later where the late release waits: it must outlive the profile that closes.
 * @param warn told of a release that failed. The log is given its kind and where it came from, never what it
 *   says: the message of a failure about a cache may quote the profile's folder.
 */
internal fun releaseAfterPlayback(
    ended: Boolean,
    running: StateFlow<Boolean>,
    later: CoroutineScope,
    warn: (Throwable) -> Unit = {
        Log.w(Tag, "What a closed profile played through could not be let go: ${it.kindAndFrames()}")
    },
    release: () -> Unit,
) {
    val guarded = { runCatching(release).onFailure(warn) }
    if (ended) {
        guarded()
    } else {
        later.launch {
            running.first { !it }
            guarded()
        }
    }
}
