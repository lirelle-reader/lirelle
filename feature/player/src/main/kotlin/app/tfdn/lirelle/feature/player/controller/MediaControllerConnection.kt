package app.tfdn.lirelle.feature.player.controller

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tfdn.lirelle.feature.player.service.PlaybackService
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often the place is read while playing: the chapter bar shows seconds. */
private val PositionEvery = 500.milliseconds

/** How long the connection stays open once nobody watches the session, nor waits for a command. */
internal val ReleaseAfter = 10.seconds

/** A session reached by a [SessionConnector]: a MediaController in the app. */
interface ConnectedSession {
    val player: Player

    /** The session's extras now. */
    val extras: Bundle

    fun send(command: PlayerCommand)

    fun release()
}

/** What a session tells besides its player's events. */
interface SessionEvents {
    fun onExtras(extras: Bundle)

    /** The session went away: the service ended, or restarted. */
    fun onDisconnected()
}

/** Connects to the playback service's session. */
fun interface SessionConnector {
    fun connect(events: SessionEvents): ListenableFuture<ConnectedSession>
}

/** The playback service's session through a [MediaController]. */
class MediaSessionConnector(private val context: Context) : SessionConnector {
    override fun connect(events: SessionEvents): ListenableFuture<ConnectedSession> {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val listener = object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) = events.onExtras(extras)

            override fun onDisconnected(controller: MediaController) = events.onDisconnected()
        }
        val future = MediaController.Builder(context, token).setListener(listener).buildAsync()
        return Futures.transform(future, ::ControllerSession, MoreExecutors.directExecutor())
    }

    private class ControllerSession(private val controller: MediaController) : ConnectedSession {
        override val player: Player get() = controller
        override val extras: Bundle get() = controller.sessionExtras

        override fun send(command: PlayerCommand) {
            controller.sendCustomCommand(command.sessionCommand, command.args())
        }

        override fun release() = controller.release()
    }
}

/**
 * The playback service's session, connected while someone watches [snapshot] or a command waits for
 * it, and released [ReleaseAfter] after. A command sent while not connected connects first, and is sent once
 * the session is reached. One the service drops while watched is made again.
 *
 * A connection that is refused or fails is tried again by itself for a while ([ConnectionRetry]), as long as
 * someone needs it — a command waiting, or a screen watching a service that runs: after a change of profile,
 * the service of the profile that closed may still be there for a moment, and refuses the controller of the
 * next one. When it gives up, the commands that waited are dropped, and the next need tries again, at once.
 *
 * The snapshot is read again at every event of the player and of the session's extras (a book loaded
 * through playback resumption announces no item transition), and every half second while playing; the
 * last one stays when the connection is released, and is emptied when the service goes away: when the
 * session drops the connection, and when [running] says the service ended, which also holds for a
 * service that ended while not connected. A connection the service dropped is made again, while watched,
 * only once the service runs again.
 *
 * It lives as long as [scope]: when that ends, the session is let go at once, and never reached nor tried
 * again. A controller that was asked for and is not used — the connection given up meanwhile — is released.
 *
 * Everything runs on the main thread, the controller's: [scope] and [executor] are on it.
 *
 * @param time tells how long a connection has been failing.
 */
class MediaControllerConnection(
    private val connector: SessionConnector,
    private val scope: CoroutineScope,
    private val executor: Executor,
    private val running: StateFlow<Boolean>,
    time: TimeSource = TimeSource.Monotonic,
) : PlayerConnection {
    private val mutableSnapshot = MutableStateFlow(SessionSnapshot())

    /** The chapters, read again only when the session's timeline changes. */
    private val chapters = ChapterCache()
    override val snapshot: StateFlow<SessionSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableConnected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    private var session: ConnectedSession? = null
    private var connecting: ListenableFuture<ConnectedSession>? = null
    private var extras = PlayerSessionExtras()
    private val ticker = Ticker(scope, PositionEvery) { refresh() }
    private var releasing: Job? = null

    private val watched: Boolean get() = mutableSnapshot.subscriptionCount.value > 0

    /**
     * The commands that wait for the session: sent once it is reached, dropped when it is given up — the tries
     * ended, or the connection on its way was abandoned because nobody watched any more.
     */
    private val waiting = mutableListOf<(ConnectedSession) -> Unit>()

    private val retry = ConnectionRetry(
        scope,
        time,
        // Not for a screen alone while the service is ended: connecting would make a new, empty one.
        needed = { waiting.isNotEmpty() || (watched && running.value) },
        again = { connect(retrying = true) },
        givenUp = waiting::clear,
    )

    private val playerEvents = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (player.isPlaying) ticker.start() else ticker.stop()
        }
    }

    init {
        scope.launch {
            mutableSnapshot.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { watched ->
                if (watched) connect() else releaseLater()
            }
        }
        scope.launch { running.collect { if (!it) clear() else if (watched) connect() } }
        // The scope ends with the profile: nothing would release the session later, and a service still held
        // could not end. It would go on, with the objects of a profile that closed, for the next one.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            executor.execute {
                waiting.clear()
                val held = connecting ?: return@execute
                forget()
                letGo(held)
            }
        }
    }

    override fun clear() {
        extras = PlayerSessionExtras()
        mutableSnapshot.value = SessionSnapshot()
    }

    override fun send(command: PlayerCommand) = withSession { it.send(command) }

    override fun playPause() = withSession {
        val player = it.player
        if (player.playsOrWill) player.pause() else player.play()
    }

    override fun setPlaying(playing: Boolean) = withSession { if (playing) it.player.play() else it.player.pause() }

    override fun seekTo(chapterIndex: Int, positionMs: Long) =
        withSession { it.player.seekTo(chapterIndex, positionMs) }

    private fun withSession(block: (ConnectedSession) -> Unit) {
        session?.let { return block(it) }
        // Its scope ended, with its profile: a command of a screen that is still leaving reaches no session.
        if (!scope.isActive) return
        waiting += block
        connect()
    }

    /**
     * Connects, unless a connection is there or on its way.
     *
     * @param retrying one more try of a connection that failed; otherwise a new need, which is tried at once
     *   and for the whole time again.
     */
    private fun connect(retrying: Boolean = false) {
        // Its scope ended, with its profile: nothing would ever release a session reached now, and it would
        // hold the service of the next profile.
        if (!scope.isActive) return
        releasing?.cancel()
        if (connecting != null) return
        if (!retrying) retry.reset()
        val attempt = Attempt()
        val future = connector.connect(attempt)
        attempt.future = future
        connecting = future
        future.addListener({
            if (connecting !== future) return@addListener
            val connected = doneOrNull(future)
            if (connected == null) {
                // Refused, or failed: forgotten, and tried again after a wait while someone needs it.
                connecting = null
                return@addListener retry.failed()
            }
            retry.reset()
            session = connected
            connected.player.addListener(playerEvents)
            extras = PlayerSessionExtras.from(connected.extras)
            refresh()
            mutableConnected.value = true
            if (connected.player.isPlaying) ticker.start()
            waiting.toList().also { waiting.clear() }.forEach { it(connected) }
            if (!watched) releaseLater()
        }, executor)
    }

    /** What the session of one connection tells; nothing once that connection is no longer the current one. */
    private inner class Attempt : SessionEvents {
        /** The connection it listens for; null until asked. */
        var future: ListenableFuture<ConnectedSession>? = null

        private val current: Boolean get() = future != null && connecting === future

        override fun onExtras(extras: Bundle) {
            if (!current) return
            this@MediaControllerConnection.extras = PlayerSessionExtras.from(extras)
            refresh()
        }

        override fun onDisconnected() {
            val mine = future
            if (mine == null || connecting !== mine) return
            val reached = session != null
            forget()
            if (!reached) {
                // Refused before it was reached, as by the service of a profile that closed: the controller
                // it was to give is not used, and the connection is tried again after a wait.
                letGo(mine)
                return retry.failed()
            }
            clear()
            // Not again while the service is ended: connecting would make a new, empty one. A screen
            // still watching (the state lingers a few seconds) is connected when the service starts.
            if (watched && running.value) connect()
        }
    }

    /**
     * Released a little after, unless watched or used again meanwhile.
     *
     * A connection still on its way then is abandoned, and the commands that waited for it are dropped with
     * it: nothing would send them before some later connection, made for another need at another moment.
     */
    private fun releaseLater() {
        releasing?.cancel()
        releasing = scope.launch {
            delay(ReleaseAfter)
            if (watched) return@launch
            val future = connecting ?: return@launch
            val reached = session != null
            forget()
            if (!reached) {
                waiting.clear()
                retry.reset()
            }
            letGo(future)
        }
    }

    private fun forget() {
        ticker.stop()
        session?.player?.removeListener(playerEvents)
        session = null
        connecting = null
        mutableConnected.value = false
    }

    private fun refresh() {
        val player = session?.player ?: return
        mutableSnapshot.value = player.snapshot(extras, chapters)
    }
}

/** [action] every [every] while started: the place, read while playing. */
private class Ticker(private val scope: CoroutineScope, private val every: Duration, private val action: () -> Unit) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(every)
                action()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}

/** Gives up the session [connection] reached, or was about to reach. */
private fun letGo(connection: ListenableFuture<ConnectedSession>) {
    if (!connection.cancel(true)) doneOrNull(connection)?.release()
}

/** The value of a future that is done; null when it failed or was cancelled. */
private fun <T> doneOrNull(future: ListenableFuture<T>): T? = runCatching { future.get() }.getOrNull()
