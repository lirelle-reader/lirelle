package app.tfdn.lirelle.feature.player.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackShutdownTest {

    private val shutdown = PlaybackShutdown()
    private val done = mutableListOf<String>()

    @Test
    fun `when the profile closes, the running service is told to stop`() = runTest {
        shutdown.register { done += "playback stopped" }

        shutdown.stopPlayback()

        assertThat(done).containsExactly("playback stopped")
    }

    @Test
    fun `a service that ended is not told any more, and stopping without one does nothing`() = runTest {
        shutdown.register { done += "playback stopped" }
        shutdown.unregister()

        shutdown.stopPlayback()

        assertThat(done).isEmpty()
    }

    @Test
    fun `while the profile is open, a service is taken, and another after it ended`() = runTest {
        assertThat(shutdown.register { done += "first stopped" }).isTrue()
        shutdown.unregister()
        assertThat(shutdown.register { done += "second stopped" }).isTrue()

        shutdown.stopPlayback()

        assertThat(done).containsExactly("second stopped")
    }

    /** The profile closed while the service was being made: nobody would ever stop it. */
    @Test
    fun `once the profile has closed, a service that comes late is refused, and never kept`() = runTest {
        shutdown.stopPlayback()

        assertThat(shutdown.register { done += "playback stopped" }).isFalse()
        shutdown.stopPlayback()

        assertThat(done).isEmpty()
    }

    @Test
    fun `a service is told to stop only once`() = runTest {
        shutdown.register { done += "playback stopped" }

        shutdown.stopPlayback()
        shutdown.stopPlayback()

        assertThat(done).containsExactly("playback stopped")
    }
}
