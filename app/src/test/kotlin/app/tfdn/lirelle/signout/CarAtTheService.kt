package app.tfdn.lirelle.signout

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.testing.AndroidAutoPackage
import app.tfdn.lirelle.feature.player.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture

/**
 * Android Auto on the phone, which draws what the car shows: the package the session takes for a car, once
 * Google signed it (the test installs it so, with `InstalledAndroidAuto`).
 */
internal const val CarHost = AndroidAutoPackage

/** More than a car is listed here. */
private const val PageSize = 100

/**
 * A car at the session of [service]: what Android Auto asks, asked of the callback the service gave its own
 * session, as Media3 would for a car that connected. The session is taken as a controller is given it, and
 * kept: a car that stays after the service's profile closed still holds it.
 *
 * Media3 keeps a session's callback to itself: it is read from the session, which is the one thing here that
 * is not as on a device.
 */
@OptIn(UnstableApi::class)
internal class CarAtTheService(service: PlaybackService) {

    private val car =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(CarHost, 0, 0, 0, 0, false, Bundle.EMPTY, false)
    private val session = checkNotNull(service.onGetSession(car)) { "The service gives no session" }
    private val callback = session.callback()

    suspend fun root(): LibraryResult<MediaItem> = callback.onGetLibraryRoot(session, car, null).answer()

    /** The first page of what [parentId] lists. */
    suspend fun children(parentId: String): LibraryResult<out List<MediaItem>> =
        callback.onGetChildren(session, car, parentId, 0, PageSize, null).answer()

    /** What a search of [text] finds, asked as a car does: the search, then its result. */
    suspend fun search(text: String): LibraryResult<out List<MediaItem>> {
        callback.onSearch(session, car, text, null).answer()
        return callback.onGetSearchResult(session, car, text, 0, PageSize, null).answer()
    }

    /** The answers arrive on the main thread, which is the test's: it is not blocked for them. */
    private suspend fun <T : Any> ListenableFuture<T>.answer(): T =
        awaited("the car is answered") { takeIf { isDone }?.get() }
}

/** The callback the session was built with, found in what Media3 made of it. */
@OptIn(UnstableApi::class)
private fun MediaLibrarySession.callback(): MediaLibrarySession.Callback {
    val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }.invoke(this)
    return generateSequence<Class<*>>(impl.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .onEach { it.isAccessible = true }
        .firstNotNullOfOrNull { it.get(impl) as? MediaLibrarySession.Callback }
        ?: error("Media3 no longer keeps a session's callback where this test reads it")
}
