package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Turning the pages, the slider, and what is asked of the pages meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderTurningTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `next and previous go from spread to spread and say which way`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.next()
        reader.next()
        assertThat(reader.state.value.spread).isEqualTo(Spread(2))
        assertThat(reader.state.value.forward).isTrue()

        reader.previous()
        assertThat(reader.state.value.spread).isEqualTo(Spread(1))
        assertThat(reader.state.value.forward).isFalse()
        assertThat(harness.pages.calls.filter { it.startsWith("show") })
            .containsExactly("show 0", "show 1", "show 2", "show 1").inOrder()
    }

    @Test
    fun `read right to left, next still goes to the following page`() = runTest {
        val rightToLeft =
            mapOf<DirectionScope, Direction>(DirectionScope.Series(SeriesId("4")) to Direction.RightToLeft)
        val harness = ComicHarness(this, settings = InMemoryComicSettingsStore(directions = rightToLeft))
        val reader = harness.reader()
        assertThat(reader.state.value.settings.direction).isEqualTo(Direction.RightToLeft)

        reader.next()
        assertThat(reader.state.value.spread).isEqualTo(Spread(1))

        reader.previous()
        assertThat(reader.state.value.spread).isEqualTo(Spread(0))
    }

    @Test
    fun `previous on the first page stays there`() = runTest {
        val reader = ComicHarness(this).reader()

        reader.events.test {
            reader.previous()

            assertThat(reader.state.value.spread).isEqualTo(Spread(0))
            expectNoEvents()
        }
    }

    @Test
    fun `the pages around are fetched only once the reader stayed 300 ms on a page`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val settles = { harness.pages.calls.filter { it.startsWith("settle") } }

        advanceTimeBy(299.milliseconds)
        assertThat(settles()).isEmpty()
        advanceTimeBy(2.milliseconds)
        assertThat(settles()).containsExactly("settle 0")

        reader.next()
        advanceTimeBy(200.milliseconds)
        reader.next()
        advanceTimeBy(200.milliseconds)
        assertThat(settles()).containsExactly("settle 0")

        advanceTimeBy(101.milliseconds)
        assertThat(settles()).containsExactly("settle 0", "settle 2").inOrder()
    }

    @Test
    fun `the slider previews a page while dragged and moves nothing`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val before = harness.pages.calls.toList()

        reader.previewPage(30)

        val state = reader.state.value
        assertThat(state.previewPage).isEqualTo(30)
        assertThat(state.label).isEqualTo(PageLabel(first = 31, last = null, count = Pages))
        assertThat(state.spread).isEqualTo(Spread(0))
        assertThat(harness.pages.calls).isEqualTo(before)
        assertThat(harness.store.positions).isEmpty()

        reader.previewPage(null)
        assertThat(reader.state.value.previewPage).isNull()
        assertThat(reader.state.value.label).isEqualTo(PageLabel(first = 1, last = null, count = Pages))
    }

    @Test
    fun `the slider released jumps to its page`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.previewPage(30)

        reader.goTo(30)

        assertThat(reader.state.value.spread).isEqualTo(Spread(30))
        assertThat(reader.state.value.previewPage).isNull()
        assertThat(harness.pages.calls).contains("jump 30")
        advanceTimeBy(400.milliseconds)
        assertThat(harness.pages.calls).doesNotContain("settle 30")
    }

    @Test
    fun `a page outside the album goes to its nearest end`() = runTest {
        val reader = ComicHarness(this).reader()

        reader.goTo(Pages + 10)

        assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 1))
    }

    @Test
    fun `the state holds the pages of the spread shown and of its neighbours`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        assertThat(reader.state.value.pages.keys).containsExactly(0, 1)

        reader.next()
        reader.next()
        harness.pages.ready(2)
        harness.pages.set(3, PageState.Failed(ApiError.ServerUnreachable))

        val state = reader.state.value
        assertThat(state.pages.keys).containsExactly(1, 2, 3)
        assertThat(state.pageState(1)).isEqualTo(PageState.Loading)
        assertThat(state.pageState(2)).isInstanceOf(PageState.Ready::class.java)
        assertThat(state.pageState(3)).isEqualTo(PageState.Failed(ApiError.ServerUnreachable))
        assertThat(state.pageState(40)).isEqualTo(PageState.Loading)
    }

    @Test
    fun `a page that failed is asked again`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.retryPage(0)

        assertThat(harness.pages.calls).contains("retry 0")
    }
}
