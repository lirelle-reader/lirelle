@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.tfdn.lirelle.feature.player.car.CarBrowser
import app.tfdn.lirelle.feature.player.car.CarControllers
import app.tfdn.lirelle.feature.player.car.CarLibraryCallback
import app.tfdn.lirelle.feature.player.car.ControllerKind
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The session's answer to its controllers: every controller (the app's screens, the notification, the
 * system's media controls, a watch) is offered the custom commands of [PlayerSessionContract] and the
 * current media buttons; a custom command is handed over as a [PlayerCommand]. The notification dismissed
 * by the user (Media3 lets a paused one be swiped away) closes the book, as "Stop listening" does.
 *
 * What a controller is offered goes by who it is ([ControllerKind]): the app's own get the whole contract;
 * other apps the skips and the chapters; a car those, the next speed and the next volume, with buttons of its
 * own ([buttons] is asked the car's), and it alone browses the library ([CarLibraryCallback], answered by the
 * car's browser) and may set the player's items, which is how a car starts what it was shown.
 *
 * Calls arrive on the main thread.
 */
@OptIn(UnstableApi::class)
class PlayerCommands(
    private val ownPackage: String,
    private val handle: (PlayerCommand) -> Unit,
    private val buttons: (car: Boolean) -> List<CommandButton>,
    private val cars: CarControllers = CarControllers.Media3,
    browser: CarBrowser? = null,
    private val resume: () -> ListenableFuture<MediaSession.MediaItemsWithStartPosition> = ::nothingToResume,
) : CarLibraryCallback(browser) {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val kind = kindOf(session, controller)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommandsFor(kind))
            .setAvailablePlayerCommands(playerCommandsFor(kind))
            .setMediaButtonPreferences(buttons(kind == ControllerKind.Car))
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(dispatch(customCommand.customAction, args)))

    /**
     * A play button with nothing loaded (a headset, a car, the system's media controls after the service
     * ended, or after a reboot): the last book, as a placeholder the session's player loads.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = resume()

    /** No controller sets the books: a book is loaded with [PlayerCommand.LoadBook]. */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Books are loaded with LOAD_BOOK"))

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Books are loaded with LOAD_BOOK"))

    /** Who [controller] is: the app's own before a car, a car before any other app. */
    override fun kindOf(session: MediaSession, controller: MediaSession.ControllerInfo): ControllerKind = when {
        isTrusted(controller.packageName, session.isMediaNotificationController(controller)) -> ControllerKind.Own
        cars.isCar(session, controller) -> ControllerKind.Car
        else -> ControllerKind.Other
    }

    /** The app itself, its notification and the system's media controls: the whole contract. */
    fun isTrusted(packageName: String, isNotification: Boolean): Boolean =
        isNotification || packageName == ownPackage || packageName == SystemUi

    /** Hands the command over; a [SessionResult] or [SessionError] code. */
    fun dispatch(action: String, args: Bundle): Int {
        val command = (if (action == Dismissed) PlayerCommand.CloseBook else PlayerCommand.of(action, args))
            ?: return SessionError.ERROR_NOT_SUPPORTED
        handle(command)
        return SessionResult.RESULT_SUCCESS
    }

    companion object {
        private const val SystemUi = "com.android.systemui"

        /** What the session is sent when the user dismisses its notification. */
        private const val Dismissed = MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY

        /** What other apps (a watch, a remote) may ask besides playing: skips and chapters. */
        private val ForOthers = listOf(
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
        )

        /** The whole contract, and the notification's dismissal, which the notification sends only if offered. */
        val sessionCommands: SessionCommands = withActions(PlayerSessionContract.actions + Dismissed)

        /** A car's too: the speed, by its one button, and the next volume at the end of a book. */
        private val ForCars = ForOthers +
            listOf(PlayerSessionContract.ACTION_NEXT_SPEED, PlayerSessionContract.ACTION_LISTEN_TO_NEXT)

        /** The player's commands for every controller but a car: never setting or changing its items. */
        val offeredPlayerCommands: Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()

        private val othersCommands: SessionCommands = withActions(ForOthers)

        /** A car browses and searches the library besides: the library commands, which no one else is given. */
        private val carCommands: SessionCommands =
            withActions(ForCars, MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)

        fun sessionCommandsFor(trusted: Boolean): SessionCommands = if (trusted) sessionCommands else othersCommands

        fun sessionCommandsFor(kind: ControllerKind): SessionCommands = when (kind) {
            ControllerKind.Own -> sessionCommands
            ControllerKind.Car -> carCommands
            ControllerKind.Other -> othersCommands
        }

        /** A car sets the player's items: that is how it starts a book it browsed, or the last one. */
        fun playerCommandsFor(kind: ControllerKind): Player.Commands = when (kind) {
            ControllerKind.Car -> MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            ControllerKind.Own, ControllerKind.Other -> offeredPlayerCommands
        }

        private fun withActions(
            actions: List<String>,
            base: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
        ) = base.buildUpon().apply { actions.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }.build()
    }
}

private fun nothingToResume(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
    Futures.immediateFailedFuture(UnsupportedOperationException("No book to resume"))
