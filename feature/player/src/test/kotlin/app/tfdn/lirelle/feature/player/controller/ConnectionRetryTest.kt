package app.tfdn.lirelle.feature.player.controller

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The connection to the playback service tried again: after a change of profile, the service of the profile
 * that closed refuses the controller of the next one until it has ended.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectionRetryTest {

    private val connector = FakeConnector()
    private val session = FakeSession(StubPlayer(), PlayerSessionExtras(SessionBooks.book).toBundle())
    private val running = MutableStateFlow(true)

    private fun TestScope.connectionIn(life: Job? = null): MediaControllerConnection {
        val context = life ?: backgroundScope.coroutineContext
        return MediaControllerConnection(
            connector,
            CoroutineScope(context + UnconfinedTestDispatcher(testScheduler)),
            Runnable::run,
            running,
            testScheduler.timeSource,
        )
    }

    private fun TestScope.watch(connection: MediaControllerConnection): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.snapshot.collect {} }

    /** The service of a profile that closed gives no session: the controller's connection fails. */
    private fun refuse() {
        connector.attempts.last().first.setException(SecurityException("Session rejected the connection request."))
    }

    private fun TestScope.pass(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    /** Each try is refused, two seconds after it was made, until none follows: the last, ten seconds in. */
    private fun TestScope.refuseUntilGivenUp() {
        do {
            val tries = connector.attempts.size
            refuse()
            pass(2.seconds)
        } while (connector.attempts.size > tries)
    }

    @Test
    fun `refused then accepted, it is connected after one more try`() = runTest {
        val connection = connectionIn()
        watch(connection)

        refuse()
        assertThat(connector.attempts).hasSize(1)
        pass(199.milliseconds)
        assertThat(connector.attempts).hasSize(1)
        pass(1.milliseconds)
        assertThat(connector.attempts).hasSize(2)
        connector.attempts[1].first.set(session)

        assertThat(connection.connected.value).isTrue()
        assertThat(connection.snapshot.value.bookId).isEqualTo(BookId("42"))
        pass(30.seconds)
        assertThat(connector.attempts).hasSize(2)
    }

    @Test
    fun `a command that found the session refused is sent once it is reached`() = runTest {
        val connection = connectionIn()

        connection.send(PlayerCommand.Forward)
        refuse()
        pass(200.milliseconds)
        connector.attempts[1].first.set(session)

        assertThat(session.sent).containsExactly(PlayerCommand.Forward)
        // Nobody watches: released a little after, as after any command.
        pass(11.seconds)
        assertThat(session.released).isTrue()
    }

    @Test
    fun `each wait is twice the one before, up to two seconds`() = runTest {
        val connection = connectionIn()
        watch(connection)
        val asked = mutableListOf<Long>()

        repeat(6) {
            refuse()
            val tries = connector.attempts.size
            while (connector.attempts.size == tries) pass(100.milliseconds)
            asked += currentTime
        }

        assertThat(asked).containsExactly(200L, 600L, 1_400L, 3_000L, 5_000L, 7_000L).inOrder()
    }

    @Test
    fun `refused for more than ten seconds, it stops trying, and the next command starts again`() = runTest {
        val connection = connectionIn()
        watch(connection)

        refuseUntilGivenUp()
        assertThat(currentTime).isAtLeast(10_000)
        val tries = connector.attempts.size
        pass(60.seconds)
        assertThat(connector.attempts).hasSize(tries)
        assertThat(connection.connected.value).isFalse()

        connection.send(PlayerCommand.Back)
        assertThat(connector.attempts).hasSize(tries + 1)
        // Tried for the whole time again.
        refuse()
        pass(200.milliseconds)
        assertThat(connector.attempts).hasSize(tries + 2)
        connector.attempts.last().first.set(session)
        assertThat(session.sent).containsExactly(PlayerCommand.Back)
    }

    @Test
    fun `the commands that waited when it stopped trying are not sent later`() = runTest {
        val connection = connectionIn()

        connection.send(PlayerCommand.Forward)
        refuseUntilGivenUp()

        connection.send(PlayerCommand.Back)
        connector.attempts.last().first.set(session)

        assertThat(session.sent).containsExactly(PlayerCommand.Back)
    }

    @Test
    fun `nothing is tried again once its scope ended`() = runTest {
        val life = Job()
        val connection = connectionIn(life)
        watch(connection)
        refuse()

        life.cancel()
        pass(60.seconds)

        assertThat(connector.attempts).hasSize(1)
    }

    /** The profile closed while the session was asked for: the answer is nobody's. */
    @Test
    fun `a connection refused after its scope ended is not tried again`() = runTest {
        val life = Job()
        val connection = connectionIn(life)
        watch(connection)
        connection.send(PlayerCommand.Forward)

        life.cancel()
        refuse()
        pass(60.seconds)

        assertThat(connector.attempts).hasSize(1)
    }

    @Test
    fun `a session reached after its scope ended is released, its commands never sent`() = runTest {
        val life = Job()
        val connection = connectionIn(life)
        connection.send(PlayerCommand.Forward)
        refuse()
        pass(200.milliseconds)
        assertThat(connector.attempts).hasSize(2)

        life.cancel()

        // The connection asked for was given up: whatever it gives is used by nobody.
        assertThat(connector.attempts[1].first.isCancelled).isTrue()
        assertThat(session.sent).isEmpty()
    }

    @Test
    fun `a connection dropped while watched is made again, and tried again when that is refused`() = runTest {
        val connection = connectionIn()
        watch(connection)
        connector.attempts[0].first.set(session)

        connector.attempts[0].second.onDisconnected()
        assertThat(connector.attempts).hasSize(2)
        refuse()
        pass(200.milliseconds)
        assertThat(connector.attempts).hasSize(3)
        val next = FakeSession(StubPlayer(), PlayerSessionExtras(SessionBooks.book).toBundle())
        connector.attempts[2].first.set(next)

        assertThat(connection.connected.value).isTrue()
        assertThat(connection.snapshot.value.bookId).isEqualTo(BookId("42"))
    }

    /** The session may say it refuses through its listener, before the connection itself fails. */
    @Test
    fun `a refusal told as a disconnection is tried again once, and its controller given up`() = runTest {
        val connection = connectionIn()
        watch(connection)

        connector.attempts[0].second.onDisconnected()
        assertThat(connector.attempts).hasSize(1)
        assertThat(connector.attempts[0].first.isCancelled).isTrue()
        pass(200.milliseconds)
        assertThat(connector.attempts).hasSize(2)

        connector.attempts[1].first.set(session)
        assertThat(connection.connected.value).isTrue()
    }

    @Test
    fun `nothing is tried again for a screen that left meanwhile`() = runTest {
        val connection = connectionIn()
        val watching = watch(connection)
        refuse()

        watching.cancel()
        pass(60.seconds)

        assertThat(connector.attempts).hasSize(1)
    }

    /** Connecting would make a new, empty service for a screen that only lingers. */
    @Test
    fun `nothing is tried again for a screen alone once the service ended, until it runs again`() = runTest {
        val connection = connectionIn()
        watch(connection)
        refuse()

        running.value = false
        pass(60.seconds)
        assertThat(connector.attempts).hasSize(1)

        running.value = true
        assertThat(connector.attempts).hasSize(2)
    }

    @Test
    fun `a command waiting is tried again for, even with the service ended`() = runTest {
        val connection = connectionIn()
        running.value = false

        connection.send(PlayerCommand.Forward)
        refuse()
        pass(200.milliseconds)

        assertThat(connector.attempts).hasSize(2)
    }
}
