package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NetworkWatcherTest {

    private val engine = FakeEngine().apply {
        items = listOf(MediaItem.fromUri("https://b/a1"))
        engineState = Player.STATE_READY
        wantsToPlay = true
    }
    private val online = MutableStateFlow(true)

    /** Whether the book loaded plays from its download. */
    private var kept = false

    private fun TestScope.watcher() = NetworkWatcher(engine, online, backgroundScope) { !kept }.also {
        engine.update()
        settle()
        it.start()
        settle()
    }

    private fun TestScope.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun TestScope.stall() {
        online.value = false
        settle()
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        settle()
    }

    private fun TestScope.networkBack() {
        engine.engineState = Player.STATE_READY
        engine.update()
        online.value = true
        settle()
    }

    @Test
    fun `playback that runs out of data without a network pauses, with the reason`() = runTest {
        val watcher = watcher()

        stall()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.NoConnection)
    }

    @Test
    fun `when the network is back, playback resumes`() = runTest {
        val watcher = watcher()
        stall()

        networkBack()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(watcher.reason.value).isNull()
    }

    @Test
    fun `when the user played and paused meanwhile, it stays paused`() = runTest {
        val watcher = watcher()
        stall()
        engine.play()
        settle()
        engine.pause()
        settle()

        networkBack()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isNull()
    }

    @Test
    fun `a load that fails for the network pauses too, and is prepared again when it is back`() = runTest {
        val watcher = watcher()
        online.value = false
        settle()

        engine.failure = PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        engine.update()
        settle()
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.NoConnection)

        online.value = true
        settle()
        assertThat(engine.prepares).isEqualTo(1)
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `buffering with a network is left to the player`() = runTest {
        val watcher = watcher()

        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        settle()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(watcher.reason.value).isNull()
    }

    @Test
    fun `losing the network while waiting for data pauses at once`() = runTest {
        val watcher = watcher()
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        settle()

        online.value = false
        settle()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.NoConnection)
    }

    @Test
    fun `a server that cannot be reached with a network says so, and waits for the user`() = runTest {
        val watcher = watcher()

        engine.failure = PlaybackException("refused", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        engine.update()
        settle()
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.ServerUnreachable)

        engine.play()
        settle()
        assertThat(watcher.reason.value).isNull()
    }

    @Test
    fun `a server unreachable for want of network becomes a pause for the network, which resumes`() = runTest {
        val watcher = watcher()
        engine.failure = PlaybackException("refused", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        engine.update()
        settle()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.ServerUnreachable)

        online.value = false
        settle()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.NoConnection)
        assertThat(watcher.willResume).isTrue()

        online.value = true
        settle()
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `leaving the book forgets a pause for the network`() = runTest {
        val watcher = watcher()
        stall()

        watcher.reset()

        assertThat(watcher.willResume).isFalse()
        assertThat(watcher.reason.value).isNull()
        engine.pause()
        settle()
        networkBack()
        assertThat(engine.wantsToPlay).isFalse()
    }

    @Test
    fun `a downloaded book that buffers without a network plays on, with no reason`() = runTest {
        kept = true
        val watcher = watcher()

        // A seek: the file on the device is read again.
        stall()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(watcher.reason.value).isNull()
        assertThat(watcher.willResume).isFalse()
    }

    @Test
    fun `losing the network while a downloaded book buffers changes nothing`() = runTest {
        kept = true
        val watcher = watcher()
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        settle()

        online.value = false
        settle()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(watcher.reason.value).isNull()
    }

    @Test
    fun `a downloaded book whose file cannot be read is not said to wait for the network or the server`() = runTest {
        kept = true
        val watcher = watcher()

        engine.failure = PlaybackException("unreadable", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        engine.update()
        settle()
        assertThat(watcher.reason.value).isNull()

        online.value = false
        settle()
        assertThat(watcher.reason.value).isNull()
        assertThat(watcher.willResume).isFalse()
    }

    @Test
    fun `the rule follows the book, a streamed one after a downloaded one pauses as before`() = runTest {
        kept = true
        val watcher = watcher()
        stall()
        assertThat(watcher.reason.value).isNull()
        engine.engineState = Player.STATE_READY
        engine.update()
        settle()

        kept = false
        engine.engineState = Player.STATE_BUFFERING
        engine.update()
        settle()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(watcher.reason.value).isEqualTo(NetworkPause.NoConnection)
    }
}
