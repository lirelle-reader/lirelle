package app.tfdn.lirelle.core.audio.cache

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How much of the book is kept around the playing position. */
data class BufferDurations(val targetBuffer: Duration, val backBuffer: Duration)

/**
 * The buffering rules: about 10 minutes ahead, 30 on an unmetered network, and 2 minutes behind so that
 * "back 15 seconds" does not go to the network again.
 */
@UnstableApi
object BufferPolicy {
    private val MeteredAhead = 10.minutes
    private val UnmeteredAhead = 30.minutes
    private val MeteredResumeBelow = 9.minutes
    private val Behind = 2.minutes
    private const val StartPlaybackMs = 1_000
    private const val RebufferPlaybackMs = 3_000

    fun durations(isUnmetered: Boolean) = BufferDurations(
        targetBuffer = if (isUnmetered) UnmeteredAhead else MeteredAhead,
        backBuffer = Behind,
    )

    /**
     * A load control that reads [isUnmetered] each time it decides whether to load more: a player cannot swap
     * its load control, but the network can change under it. The delegate allows the larger target; a metered
     * network stops loading at the smaller one.
     */
    fun loadControl(isUnmetered: () -> Boolean): LoadControl = MeteredGate(
        delegate = build(durations(isUnmetered = true)),
        isUnmetered = isUnmetered,
        meteredLimit = MeteredAhead,
        resumeBelow = MeteredResumeBelow,
    )

    private fun build(durations: BufferDurations): DefaultLoadControl {
        val target = durations.targetBuffer.inWholeMilliseconds.toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(target, target, StartPlaybackMs, RebufferPlaybackMs)
            // Audio at 64 to 128 kbps would hit the default byte cap long before 30 minutes.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(durations.backBuffer.inWholeMilliseconds.toInt(), false)
            .build()
    }
}

/**
 * Forwards to [delegate], except that a metered network stops loading at [meteredLimit] and starts again only
 * below [resumeBelow]: loading in short bursts at the limit would keep the radio busy.
 */
@UnstableApi
private class MeteredGate(
    private val delegate: LoadControl,
    private val isUnmetered: () -> Boolean,
    private val meteredLimit: Duration,
    private val resumeBelow: Duration,
) : LoadControl {
    private var refusing = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (isUnmetered()) {
            refusing = false
        } else {
            val buffered = parameters.bufferedDurationUs
            refusing = buffered >= meteredLimit.inWholeMicroseconds ||
                (refusing && buffered >= resumeBelow.inWholeMicroseconds)
            if (refusing) return false
        }
        return delegate.shouldContinueLoading(parameters)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    // The overload Media3 1.11 calls; the older ones throw by default.
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)
}
