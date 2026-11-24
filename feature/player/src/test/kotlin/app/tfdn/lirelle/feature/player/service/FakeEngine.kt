package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player that plays nothing: its playlist, place, play/pause, speed and volume are fields a test sets
 * and reads. Positions do not move by themselves; set [index] and [positionMs], then [update].
 */
@UnstableApi
class FakeEngine : SimpleBasePlayer(Looper.getMainLooper()) {
    var items: List<MediaItem> = emptyList()
    var durationsMs: List<Long> = emptyList()
    var index = 0
    var positionMs = 0L
    var bufferedMs = 0L
    var wantsToPlay = false
    var engineState = Player.STATE_IDLE
    var failure: PlaybackException? = null
    var speed = 1f
    var engineVolume = 1f
    var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    val seeks = mutableListOf<Pair<Int, Long>>()
    var prepares = 0
    var stops = 0

    /** The player reports its fields again (and its listeners hear what changed). */
    fun update() = invalidateState()

    override fun getState(): State {
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder("item-$i")
                .setMediaItem(item)
                .setDurationUs(durationsMs.getOrNull(i)?.let { it * 1_000 } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val state = if (items.isEmpty() && engineState != Player.STATE_ENDED) Player.STATE_IDLE else engineState
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(positionMs)
            .setContentBufferedPositionMs { bufferedMs }
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (failure != null) Player.STATE_IDLE else state)
            .setPlayerError(failure)
            .setPlaybackSuppressionReason(suppression)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(engineVolume)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        items = items.filterIndexed { i, _ -> i !in fromIndex until toIndex }
        if (items.isEmpty()) index = 0
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
        this.positionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
        seeks += index to this.positionMs
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsToPlay = playWhenReady
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepares++
        failure = null
        if (items.isNotEmpty()) engineState = Player.STATE_READY
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        stops++
        engineState = Player.STATE_IDLE
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return done()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        engineVolume = volume
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> = done()

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
