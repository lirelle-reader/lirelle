@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.content.pm.PackageManager
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
         * Android Auto on the phone: what Media3 takes for it, which goes by the controller's package name, and
         * that Google signed the package of that name ([isAndroidAuto]), asked of [packages]. The name alone is
         * no proof of who asks where Android Auto is not installed. A car that runs Android by itself is not
         * one: the app is not made for it.
         */
        fun androidAuto(packages: PackageManager) = CarControllers { session, controller ->
            session.isAutoCompanionController(controller) && packages.isAndroidAuto(controller.packageName)
        }
    }
}
