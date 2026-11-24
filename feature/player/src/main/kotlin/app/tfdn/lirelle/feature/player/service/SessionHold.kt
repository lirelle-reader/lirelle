@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSessionService

/**
 * The media session of the playback service, until it is let go — which happens once, at the first of two
 * moments: its profile closes, or the service ends.
 *
 * A profile that closes must not leave its service alive, and a service lives as long as a controller is bound
 * to it: the system's media controls, or another app (Android Auto), which nothing in the app can ask to leave.
 * Releasing the session disconnects the Media3 controllers, so that they no longer hold the service, which
 * ends, and the next profile gets a new one, made of its own objects. A client bound through the legacy media
 * browser (Android Auto may be one) is not known to be unbound by it and may stay bound: the device check
 * verifies what happens then. The service may live a little longer with no session: whoever still speaks to
 * the session goes through [held], and finds none.
 *
 * The session is first taken out of the service ([MediaSessionService.removeSession]), when the service had
 * been given it for a controller: the service then keeps no notification for it, and never reaches a session
 * that was released. Call it on the main thread.
 */
internal class SessionHold(private val service: MediaSessionService, private val session: MediaLibrarySession) {

    /** Whether the session was let go: it is never released twice. */
    var released = false
        private set

    /** The session while it can be used; null once let go. */
    val held: MediaLibrarySession? get() = session.takeUnless { released }

    /** Lets the session go, disconnecting its Media3 controllers. Does nothing the second time. */
    fun release() {
        if (released) return
        released = true
        // Only a session the service was given: removing one it does not know is an error.
        if (service.isSessionAdded(session)) service.removeSession(session)
        session.release()
    }
}
