@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The library half of the session's callback: a car is answered by its browser ([library]), and anyone else is
 * refused — the library is the car's alone. The other half, the player's commands, is its subclass, which says
 * who a controller is ([kindOf]).
 *
 * A subscription is Media3's own: it asks for the item of the place ([onGetItem]) and accepts a folder.
 *
 * @param library null where nothing is browsed: every question is then unsupported.
 */
abstract class CarLibraryCallback(private val library: CarBrowser?) : MediaLibrarySession.Callback {

    /** Who [controller] is. */
    abstract fun kindOf(session: MediaSession, controller: MediaSession.ControllerInfo): ControllerKind

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = forCar(session, browser) { root() }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = forCar(session, browser) { item(mediaId) }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        forCar(session, browser) { children(it, parentId, page, pageSize) }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = forCar(session, browser) { search(it, query, params) }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        forCar(session, browser) { searchResult(it, query, page, pageSize) }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        library?.forget(controller)
    }

    /** What [ask] answers to a car; a refusal to anyone else. */
    private fun <T : Any> forCar(
        session: MediaLibrarySession,
        controller: MediaSession.ControllerInfo,
        ask: CarBrowser.(CarLink) -> ListenableFuture<LibraryResult<T>>,
    ): ListenableFuture<LibraryResult<T>> = when {
        library == null -> Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        kindOf(session, controller) != ControllerKind.Car ->
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        else -> library.ask(SessionCarLink(session, controller))
    }
}

/** A car as the session knows it: what it is told goes through the session. */
private class SessionCarLink(
    private val session: MediaLibrarySession,
    override val controller: MediaSession.ControllerInfo,
) : CarLink {
    override fun say(error: SessionError) = session.sendError(controller, error)

    override fun searchChanged(query: String, count: Int, params: LibraryParams?) =
        session.notifySearchResultChanged(controller, query, count, params)
}
