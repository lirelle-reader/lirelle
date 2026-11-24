package app.tfdn.lirelle.feature.player.controller

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MediaControllerConnectionTest {

    private val connector = FakeConnector()
    private val player = StubPlayer()
    private val session = FakeSession(player, PlayerSessionExtras(SessionBooks.book).toBundle())

    /** Whether the playback service runs; it does, unless a test stops it. */
    private val running = MutableStateFlow(true)

    private fun TestScope.connection() = MediaControllerConnection(
        connector,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        Runnable::run,
        running,
        testScheduler.timeSource,
    )

    /** A connection whose scope the test ends, as a profile's does when it closes. */
    private fun TestScope.connectionIn(life: Job) = MediaControllerConnection(
        connector,
        CoroutineScope(life + UnconfinedTestDispatcher(testScheduler)),
        Runnable::run,
        running,
        testScheduler.timeSource,
    )

    @Test
    fun `its scope ending lets the session go at once`() = runTest {
        val life = Job()
        val connection = connectionIn(life)
        watch(connection)
        connector.attempts[0].first.set(session)

        life.cancel()

        assertThat(session.released).isTrue()
        assertThat(connection.connected.value).isFalse()
    }

    /** Nothing could release it: it would hold the service of the next profile. */
    @Test
    fun `once its scope ended, a command of a screen still leaving reaches no session`() = runTest {
        val life = Job()
        val connection = connectionIn(life)
        life.cancel()

        connection.send(PlayerCommand.Forward)
        connection.playPause()
        connection.seekTo(0, 0)

        assertThat(connector.attempts).isEmpty()
    }

    private fun TestScope.watch(connection: MediaControllerConnection): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.snapshot.collect {} }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `watching connects, and the snapshot shows the session`() = runTest {
        val connection = connection()
        watch(connection)

        assertThat(connector.attempts).hasSize(1)
        connector.attempts[0].first.set(session)

        assertThat(connection.snapshot.value.bookId).isEqualTo(BookId("42"))
        assertThat(connection.snapshot.value.chapters).hasSize(1)
    }

    @Test
    fun `connected once the session is reached, with its snapshot read, and no longer once released`() = runTest {
        val connection = connection()
        val watching = watch(connection)
        assertThat(connection.connected.value).isFalse()

        connector.attempts[0].first.set(session)
        assertThat(connection.connected.value).isTrue()

        watching.cancel()
        advanceTimeBy(11.seconds)
        assertThat(connection.connected.value).isFalse()
    }

    @Test
    fun `the service ending empties the snapshot, even one kept after the connection was released`() = runTest {
        val connection = connection()
        val watching = watch(connection)
        connector.attempts[0].first.set(session)
        watching.cancel()
        advanceTimeBy(11.seconds)
        assertThat(connection.snapshot.value.bookId).isEqualTo(BookId("42"))

        running.value = false

        assertThat(connection.snapshot.value).isEqualTo(SessionSnapshot())
    }

    @Test
    fun `a service that ended while watched is not made again, until it starts again`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        running.value = false
        connector.attempts[0].second.onDisconnected()
        assertThat(connector.attempts).hasSize(1)

        running.value = true
        assertThat(connector.attempts).hasSize(2)
    }

    @Test
    fun `cleared, the session's extras are forgotten with its snapshot`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        connection.clear()

        assertThat(connection.snapshot.value).isEqualTo(SessionSnapshot())
        // The next event of the player reads the session again, without the extras forgotten.
        player.startPlaying()
        idle()
        assertThat(connection.snapshot.value.bookId).isNull()
    }

    @Test
    fun `the session's extras changing changes the snapshot`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        connector.attempts[0].second.onExtras(PlayerSessionExtras(SessionBooks.book, offline = true).toBundle())

        assertThat(connection.snapshot.value.extras.offline).isTrue()
    }

    @Test
    fun `released ten seconds after nobody watches, and connected again when watched`() = runTest {
        val connection = connection()
        val watching = watch(connection)
        connector.attempts[0].first.set(session)

        watching.cancel()
        advanceTimeBy(9.seconds)
        assertThat(session.released).isFalse()
        advanceTimeBy(2.seconds)
        assertThat(session.released).isTrue()
        assertThat(connection.snapshot.value.bookId).isEqualTo(BookId("42"))

        watch(connection)
        assertThat(connector.attempts).hasSize(2)
    }

    @Test
    fun `watched again in time, the connection stays`() = runTest {
        val connection = connection()
        val watching = watch(connection)
        connector.attempts[0].first.set(session)

        watching.cancel()
        advanceTimeBy(5.seconds)
        watch(connection)
        advanceTimeBy(30.seconds)

        assertThat(session.released).isFalse()
        assertThat(connector.attempts).hasSize(1)
    }

    @Test
    fun `a connection that failed is tried again at the next need`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.setException(IllegalStateException("no service"))

        connection.send(PlayerCommand.Back)
        assertThat(connector.attempts).hasSize(2)
        connector.attempts[1].first.set(session)

        assertThat(session.sent).containsExactly(PlayerCommand.Back)
    }

    @Test
    fun `the service going away while watched empties the snapshot and connects again`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        connector.attempts[0].second.onDisconnected()

        assertThat(connection.snapshot.value).isEqualTo(SessionSnapshot())
        assertThat(connector.attempts).hasSize(2)
    }

    @Test
    fun `a command without a connection connects, is sent, and the connection is released after`() = runTest {
        val connection = connection()

        connection.send(PlayerCommand.Forward)
        connector.attempts.single().first.set(session)
        assertThat(session.sent).containsExactly(PlayerCommand.Forward)

        advanceTimeBy(11.seconds)
        assertThat(session.released).isTrue()
    }

    /** It was asked for a session that never came: sent minutes later, it would act on another moment. */
    @Test
    fun `a command does not outlive the connection attempt that was abandoned`() = runTest {
        val connection = connection()
        connection.send(PlayerCommand.Forward)
        val watching = watch(connection)

        watching.cancel()
        advanceTimeBy(11.seconds)
        assertThat(connector.attempts.single().first.isCancelled).isTrue()

        watch(connection)
        assertThat(connector.attempts).hasSize(2)
        connector.attempts[1].first.set(session)

        assertThat(session.sent).isEmpty()
    }

    @Test
    fun `play or pause is decided from the session itself`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        player.startPlaying()
        idle()
        connection.playPause()
        idle()
        connection.playPause()
        idle()

        assertThat(player.asked).containsExactly("pause", "play").inOrder()
    }

    @Test
    fun `play only plays, whatever the session does`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        connection.play()
        idle()
        player.startPlaying()
        idle()
        connection.play()
        idle()

        assertThat(player.asked).contains("play")
        assertThat(player.asked).doesNotContain("pause")
    }

    @Test
    fun `pause only pauses, whatever the session does`() = runTest {
        val connection = connection()
        watch(connection)
        connector.attempts[0].first.set(session)

        player.startPlaying()
        idle()
        connection.pause()
        idle()
        connection.pause()
        idle()

        assertThat(player.asked).contains("pause")
        assertThat(player.asked).doesNotContain("play")
    }
}
