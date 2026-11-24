package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskRemovalTest {

    @Test
    fun `swiping the app away stops a paused, stopped or finished book`() {
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = false, Player.STATE_READY, false)).isTrue()
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = true, Player.STATE_IDLE, false)).isTrue()
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = true, Player.STATE_ENDED, false)).isTrue()
    }

    @Test
    fun `but not one that plays, waits for data or is held by a call`() {
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = true, Player.STATE_READY, false)).isFalse()
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = true, Player.STATE_BUFFERING, false)).isFalse()
    }

    @Test
    fun `nor one paused for the network that resumes by itself`() {
        assertThat(PlaybackService.stopsWhenTaskRemoved(playWhenReady = false, Player.STATE_IDLE, true)).isFalse()
    }
}
