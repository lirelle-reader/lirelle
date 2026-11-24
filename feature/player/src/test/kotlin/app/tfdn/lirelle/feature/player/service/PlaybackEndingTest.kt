package app.tfdn.lirelle.feature.player.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A profile closes while its book plays: what its closer waits for before the profile's stores go. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEndingTest {

    private val shutdown = PlaybackShutdown()
    private val running = MutableStateFlow(true)
    private val done = mutableListOf<String>()

    private suspend fun end() = endPlayback(shutdown, running) { done += "controller released" }

    @Test
    fun `with no service running, the controller is let go and nothing is waited for`() = runTest {
        running.value = false

        assertThat(end()).isTrue()

        assertThat(done).containsExactly("controller released")
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test
    fun `the controller is let go first, then the service stopped, and its last writes waited for`() = runTest {
        val written = CompletableDeferred<Unit>()
        shutdown.register {
            done += "book closed"
            written.await()
            done += "last writes made"
            running.value = false
        }
        val ending = async(UnconfinedTestDispatcher(testScheduler)) { end() }
        assertThat(done).containsExactly("controller released", "book closed").inOrder()
        assertThat(ending.isCompleted).isFalse()

        written.complete(Unit)

        assertThat(ending.await()).isTrue()
        assertThat(done).containsExactly("controller released", "book closed", "last writes made").inOrder()
    }

    /** Stopping is asked, not done: the service ends later, once nothing holds it. */
    @Test
    fun `the service is waited for until it no longer runs`() = runTest {
        shutdown.register { done += "book closed" }
        val ending = async(UnconfinedTestDispatcher(testScheduler)) { end() }
        assertThat(done).contains("book closed")
        assertThat(ending.isCompleted).isFalse()

        running.value = false

        assertThat(ending.await()).isTrue()
    }

    @Test
    fun `a service that does not end is given up on, in less time than a closer has`() = runTest {
        shutdown.register { done += "book closed" }
        val ending = async(UnconfinedTestDispatcher(testScheduler)) { end() }

        advanceTimeBy(PlaybackEndWaitMillis - 1)
        assertThat(ending.isCompleted).isFalse()
        advanceTimeBy(2)

        assertThat(ending.await()).isFalse()
        assertThat(PlaybackEndWaitMillis).isLessThan(5_000L)
    }

    @Test
    fun `a book that cannot be closed in time is given up on as well`() = runTest {
        shutdown.register { awaitCancellation() }
        val ending = async(UnconfinedTestDispatcher(testScheduler)) { end() }

        advanceTimeBy(PlaybackEndWaitMillis + 1)

        assertThat(ending.await()).isFalse()
    }

    @Test
    fun `what the player read through is let go at once when playback has ended`() = runTest {
        running.value = false

        releaseAfterPlayback(ended = true, running, backgroundScope) { done += "cache released" }

        assertThat(done).containsExactly("cache released")
    }

    @Test
    fun `it is not let go under a service that still runs, but as soon as that one ends`() = runTest {
        val later = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        releaseAfterPlayback(ended = false, running, later) { done += "cache released" }
        assertThat(done).isEmpty()

        running.value = false

        assertThat(done).containsExactly("cache released")
    }

    /** The next account signed in, and plays, before the service of the one before has ended. */
    @Test
    fun `the service the next profile starts does not hold what the one before played through`() = runTest {
        val before = PlaybackShutdown().apply { playerMade() }
        val next = PlaybackShutdown()
        val later = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        releaseAfterPlayback(ended = false, before.reading, later) { done += "cache released" }

        next.playerMade()
        assertThat(done).isEmpty()
        before.playerReleased()

        assertThat(done).containsExactly("cache released")
        assertThat(next.reading.value).isTrue()
    }

    @Test
    fun `a release that fails at once is told, and fails nothing`() = runTest {
        val told = mutableListOf<Throwable>()
        val failure = IllegalStateException("still locked")
        running.value = false

        releaseAfterPlayback(ended = true, running, backgroundScope, { told += it }) { throw failure }

        assertThat(told).containsExactly(failure)
    }

    /** Where the late release waits nobody catches a failure: uncaught, it would end the app. */
    @Test
    fun `a late release that fails is told, and does not fail where it waited`() = runTest {
        val told = mutableListOf<Throwable>()
        val failure = IllegalStateException("still locked")
        // No handler, as where it waits in the app: a failure not caught here fails this test.
        val later = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        releaseAfterPlayback(ended = false, running, later, { told += it }) { throw failure }
        assertThat(told).isEmpty()

        running.value = false

        assertThat(told).containsExactly(failure)
        assertThat(later.isActive).isTrue()
    }
}
