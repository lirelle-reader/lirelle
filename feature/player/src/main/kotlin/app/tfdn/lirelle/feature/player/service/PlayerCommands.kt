@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The session's answer to its controllers: every controller (the app's screens, the notification, the
 * system's media controls, a watch) is offered the custom commands of [PlayerSessionContract] and the
 * current media buttons; a custom command is handed over as a [PlayerCommand]. The notification dismissed
 * by the user (Media3 lets a paused one be swiped away) closes the book, as "Stop listening" does.
 *
 * Calls arrive on the main thread.
 */
@OptIn(UnstableApi::class)
class PlayerCommands(
    private val ownPackage: String,
    private val handle: (PlayerCommand) -> Unit,
    private val buttons: () -> List<CommandButton>,
    private val resume: () -> ListenableFuture<MediaSession.MediaItemsWithStartPosition> = ::nothingToResume,
) : MediaLibrarySession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val trusted = isTrusted(controller.packageName, session.isMediaNotificationController(controller))
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommandsFor(trusted))
            .setAvailablePlayerCommands(offeredPlayerCommands)
            .setMediaButtonPreferences(buttons())
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

        /** What other apps (a watch, a car, a remote) may ask besides playing: skips and chapters. */
        private val ForOthers = listOf(
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
        )

        /** The whole contract, and the notification's dismissal, which the notification sends only if offered. */
        val sessionCommands: SessionCommands = withActions(PlayerSessionContract.actions + Dismissed)

        /** The player's commands for every controller: never setting or changing its items. */
        val offeredPlayerCommands: Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()

        private val othersCommands: SessionCommands = withActions(ForOthers)

        fun sessionCommandsFor(trusted: Boolean): SessionCommands = if (trusted) sessionCommands else othersCommands

        private fun withActions(actions: List<String>) =
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                actions.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }.build()
    }
}

private fun nothingToResume(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
    Futures.immediateFailedFuture(UnsupportedOperationException("No book to resume"))
