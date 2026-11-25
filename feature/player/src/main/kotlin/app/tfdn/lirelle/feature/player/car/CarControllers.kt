@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Who a controller of the session is: the app itself (its screens, its notification, the system's media
 * controls), a car, or any other app (a watch, a remote).
 */
enum class ControllerKind { Own, Car, Other }

/** Tells the car from the other controllers of the session. */
fun interface CarControllers {
    fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean

    companion object {
        /**
         * Media3's own check: Android Auto on the phone, or the media app of a car that runs Android. It goes
         * by the controller's package name, which is no proof of who asks: the car is given nothing a stranger
         * should not have.
         */
        val Media3 = CarControllers { session, controller ->
            session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)
        }
    }
}
