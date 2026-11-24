package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import app.tfdn.lirelle.feature.player.controller.SessionSnapshot
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** A player made again from its saved state leaves by itself once the session shows nothing loaded. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRestoredTest {

    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var harness: PlayerHarness
    private val events = mutableListOf<PlayerEvent>()

    /** A player made again, the session not reached yet: what it shows is the empty first snapshot. */
    private fun TestScope.restored(): PlayerViewModel {
        harness = PlayerHarness(this)
        harness.connection.connected.value = false
        return harness.viewModel().also { viewModel ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { events += it }
            }
            viewModel.leaveIfNothingLoaded()
        }
    }

    @Test
    fun `the empty snapshot before the session is reached does not count`() = runTest {
        restored()

        assertThat(events).isEmpty()
    }

    @Test
    fun `the session reached without a book, the player leaves`() = runTest {
        restored()

        harness.connection.connected.value = true

        assertThat(events).containsExactly(PlayerEvent.Leave)
    }

    @Test
    fun `asked again by the same screen, after a rotation or back from the settings, it still leaves once`() = runTest {
        val viewModel = restored()

        viewModel.leaveIfNothingLoaded()
        viewModel.leaveIfNothingLoaded()
        harness.connection.connected.value = true

        assertThat(events).containsExactly(PlayerEvent.Leave)
    }

    @Test
    fun `a service that does not run holds no book, so the player leaves without connecting to it`() = runTest {
        harness = PlayerHarness(this)
        harness.running.value = false
        harness.connection.connected.value = false
        val viewModel = harness.viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.leaveIfNothingLoaded()

        assertThat(events).containsExactly(PlayerEvent.Leave)
        assertThat(harness.connection.snapshot.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun `a book in the session keeps the player`() = runTest {
        restored()

        harness.show(SessionBooks.at())
        harness.connection.connected.value = true

        assertThat(events).isEmpty()
    }

    @Test
    fun `a book being read keeps the player, and so does one that could not be`() = runTest {
        restored()

        harness.show(SessionSnapshot(PlayerSessionExtras(book = null, loading = true)))
        harness.connection.connected.value = true
        harness.show(SessionSnapshot(PlayerSessionExtras(book = null, loadFailed = true)))

        assertThat(events).isEmpty()
    }
}
