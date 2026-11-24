package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val NetworkErrors =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

/** Why playback was paused for the network. */
enum class NetworkPause {
    /** No network: playback resumes by itself when it is back. */
    NoConnection,

    /** A network, but the server cannot be reached: the user resumes. */
    ServerUnreachable,
}

/**
 * Pauses cleanly when the network is gone and playback has used what was buffered (or a load failed for the
 * network), with the reason in [reason]; resumes by itself when the network is back, unless the user played
 * or paused meanwhile. Losing the network while waiting for data pauses at once. A load that fails while the
 * device has a network means the server cannot be reached: no "back" will come, the user resumes. Call it on
 * the main thread.
 *
 * A book that plays from its download waits for no network: its buffering (a seek reads the file again) and its
 * failures are left to the player, with no reason given.
 *
 * @param needsNetwork whether the book loaded is read from the server; asked each time something happens.
 */
class NetworkWatcher(
    private val engine: Player,
    private val online: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val needsNetwork: () -> Boolean = { true },
) : Player.Listener {
    private val mutableReason = MutableStateFlow<NetworkPause?>(null)
    val reason: StateFlow<NetworkPause?> = mutableReason.asStateFlow()

    private var resumeWhenBack = false

    /** Paused for the network, and resuming by itself when it is back. */
    val willResume: Boolean get() = resumeWhenBack
    private var acting = false
    private var watching: Job? = null

    fun start() {
        engine.addListener(this)
        watching = scope.launch { online.collect { if (it) back() else lost() } }
    }

    fun stop() {
        engine.removeListener(this)
        watching?.cancel()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val stalled = playbackState == Player.STATE_BUFFERING && engine.playWhenReady
        if (stalled && !online.value && needsNetwork()) pause(NetworkPause.NoConnection)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode !in NetworkErrors || !engine.playWhenReady || !needsNetwork()) return
        if (online.value) pause(NetworkPause.ServerUnreachable) else pause(NetworkPause.NoConnection)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (acting || !playWhenReady) return
        // The user played again: their choice from now on.
        resumeWhenBack = false
        mutableReason.value = null
    }

    /** The book was left or replaced: a pause for the network belonged to it. */
    fun reset() {
        resumeWhenBack = false
        mutableReason.value = null
    }

    private fun lost() {
        if (mutableReason.value == NetworkPause.ServerUnreachable) {
            // It was the network after all: resume when it is back.
            resumeWhenBack = true
            mutableReason.value = NetworkPause.NoConnection
            return
        }
        val stalled = engine.playbackState == Player.STATE_BUFFERING && engine.playWhenReady
        if (stalled && needsNetwork()) pause(NetworkPause.NoConnection)
    }

    /** Paused for [reason]; only a lost network resumes by itself. */
    private fun pause(reason: NetworkPause) {
        resumeWhenBack = reason == NetworkPause.NoConnection
        mutableReason.value = reason
        act { engine.pause() }
    }

    private fun back() {
        if (mutableReason.value == NetworkPause.NoConnection) mutableReason.value = null
        if (!resumeWhenBack) return
        resumeWhenBack = false
        act {
            if (engine.playbackState == Player.STATE_IDLE) engine.prepare()
            engine.play()
        }
    }

    private fun act(change: () -> Unit) {
        acting = true
        try {
            change()
        } finally {
            acting = false
        }
    }
}
