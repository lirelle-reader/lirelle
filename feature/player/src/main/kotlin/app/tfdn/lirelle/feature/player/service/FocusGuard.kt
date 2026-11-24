package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After a short interruption (a navigation prompt, a notification sound, a short call) playback resumes by
 * itself, as the engine does; after one longer than [limit] it stays paused: the listener has moved on. Call
 * it on the main thread.
 */
class FocusGuard(
    private val engine: Player,
    private val scope: CoroutineScope,
    private val limit: Duration = 2.minutes,
) : Player.Listener {
    private var waiting: Job? = null

    fun start() = engine.addListener(this)

    fun stop() {
        engine.removeListener(this)
        waiting?.cancel()
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        waiting?.cancel()
        if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) return
        waiting = scope.launch {
            delay(limit)
            val stillLost =
                engine.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            if (stillLost && engine.playWhenReady) engine.pause()
        }
    }
}
