package app.tfdn.lirelle.feature.player.controller

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** A session's player, playing or not, that records what it is asked. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    val asked = mutableListOf<String>()
    private var playing = false

    fun startPlaying() {
        playing = true
        invalidateState()
    }

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(MediaItemData.Builder("42/chapter-0").setMediaItem(MediaItem.EMPTY).build()))
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(Player.STATE_READY)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        asked += if (playWhenReady) "play" else "pause"
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }
}

internal class FakeSession(override val player: Player, override var extras: Bundle) : ConnectedSession {
    val sent = mutableListOf<PlayerCommand>()
    var released = false

    override fun send(command: PlayerCommand) {
        sent += command
    }

    override fun release() {
        released = true
    }
}

/** Each connection asked, waiting for the test to make it succeed or fail. */
internal class FakeConnector : SessionConnector {
    val attempts = mutableListOf<Pair<SettableFuture<ConnectedSession>, SessionEvents>>()

    override fun connect(events: SessionEvents): ListenableFuture<ConnectedSession> =
        SettableFuture.create<ConnectedSession>().also { attempts += it to events }
}
