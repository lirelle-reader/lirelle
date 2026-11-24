package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private val Unreachable = PageState.Failed(ApiError.ServerUnreachable)
private val OpeningFailed = ComicPhase.Failed(ComicFailure.Load(ApiError.ServerUnreachable))

/** A reader in front, its album open at its first page, and what was asked of the pages forgotten. */
private fun ComicHarness.readerInFront(): ComicReaderViewModel = reader().also {
    it.onForeground()
    pages.calls.clear()
}

private fun ComicHarness.networkReturns() {
    online.value = false
    online.value = true
}

/** The network comes back: what failed for want of it is asked again, once, by a reader in front. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderNetworkReturnTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the page shown that failed for want of network is asked again when it comes back`() = runTest {
        val harness = ComicHarness(this)
        harness.readerInFront()
        harness.online.value = false
        harness.pages.set(0, Unreachable)
        assertThat(harness.pages.calls).isEmpty()

        harness.online.value = true

        assertThat(harness.pages.calls).containsExactly("retry 0", "settle 0").inOrder()
    }

    @Test
    fun `both pages of a spread are asked again`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.readerInFront()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.next()
        assertThat(reader.state.value.spread).isEqualTo(Spread(1, 2))
        harness.pages.set(1, Unreachable)
        harness.pages.set(2, Unreachable)
        harness.pages.calls.clear()

        harness.networkReturns()

        assertThat(harness.pages.calls).containsExactly("retry 1", "retry 2", "settle 1").inOrder()
    }

    @Test
    fun `a failed page beside the spread shown is left to the pages fetched ahead`() = runTest {
        val harness = ComicHarness(this)
        harness.readerInFront()
        harness.pages.ready(0)
        harness.pages.set(1, Unreachable)

        harness.networkReturns()

        assertThat(harness.pages.calls).containsExactly("settle 0")
    }

    @Test
    fun `a page the server no longer has is not asked again`() = runTest {
        val harness = ComicHarness(this)
        harness.readerInFront()
        harness.pages.set(0, PageState.Failed(ApiError.NotFound))

        harness.networkReturns()

        assertThat(harness.pages.calls).isEmpty()
    }

    @Test
    fun `nothing is asked while the network stays`() = runTest {
        val harness = ComicHarness(this)
        harness.readerInFront()
        harness.pages.set(0, Unreachable)

        harness.online.value = true

        assertThat(harness.pages.calls).isEmpty()
    }

    @Test
    fun `a page is asked once for each return of the network`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.readerInFront()
        harness.pages.set(0, Unreachable)
        harness.networkReturns()
        assertThat(harness.pages.calls).contains("retry 0")
        harness.pages.calls.clear()

        // The new fetch fails too: the page says so, and nothing more is asked until the user does.
        harness.pages.set(0, PageState.Loading)
        harness.pages.set(0, Unreachable)
        assertThat(harness.pages.calls).isEmpty()
        assertThat(reader.state.value.pageState(0)).isEqualTo(Unreachable)

        reader.retryPage(0)
        assertThat(harness.pages.calls).containsExactly("retry 0")
    }

    @Test
    fun `a reader behind asks nothing, and asks as it comes back to the front`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.readerInFront()
        harness.pages.set(0, Unreachable)
        reader.onBackground()

        harness.networkReturns()
        assertThat(harness.pages.calls).isEmpty()

        reader.onForeground()
        assertThat(harness.pages.calls).containsExactly("retry 0", "settle 0").inOrder()

        // What was owed is done: coming to the front again asks nothing.
        harness.pages.calls.clear()
        reader.onBackground()
        reader.onForeground()
        assertThat(harness.pages.calls).isEmpty()
    }

    @Test
    fun `a reader that comes back to the front after the network went again asks nothing`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.readerInFront()
        harness.pages.set(0, Unreachable)
        reader.onBackground()
        harness.networkReturns()
        harness.online.value = false

        reader.onForeground()

        assertThat(harness.pages.calls).isEmpty()
    }

    @Test
    fun `an album that could not be opened for want of network is opened when it comes back`() = runTest {
        val harness = ComicHarness(this)
        harness.online.value = false
        harness.pages.countResult = PageCountResult.Failed(ApiError.ServerUnreachable)
        val reader = harness.reader()
        reader.onForeground()
        assertThat(reader.state.value.phase).isEqualTo(OpeningFailed)

        harness.pages.countResult = PageCountResult.Known(Pages)
        harness.online.value = true

        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Ready)
        assertThat(harness.pages.countAsked).isEqualTo(2)
    }

    @Test
    fun `an opening that fails again is not tried a third time`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Failed(ApiError.ServerUnreachable)
        val reader = harness.reader()
        reader.onForeground()

        harness.networkReturns()

        assertThat(reader.state.value.phase).isEqualTo(OpeningFailed)
        assertThat(harness.pages.countAsked).isEqualTo(2)
    }

    @Test
    fun `an album the server no longer has is not opened again`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Failed(ApiError.NotFound)
        val reader = harness.reader()
        reader.onForeground()

        harness.networkReturns()

        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Load(ApiError.NotFound)))
        assertThat(harness.pages.countAsked).isEqualTo(1)
    }
}
