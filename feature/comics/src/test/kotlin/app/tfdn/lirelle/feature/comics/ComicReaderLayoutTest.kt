package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** One page or two: the window, the setting, the wide pages, and what the bar says of them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderLayoutTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `two pages show on a tablet in landscape only`() = runTest {
        val reader = ComicHarness(this).reader()

        reader.windowSize(widthDp = 411, heightDp = 891)
        assertThat(reader.state.value.twoPages).isFalse()

        reader.windowSize(widthDp = 891, heightDp = 411)
        assertThat(reader.state.value.twoPages).isFalse()

        reader.windowSize(widthDp = 800, heightDp = 1280)
        assertThat(reader.state.value.twoPages).isFalse()

        reader.windowSize(widthDp = 1280, heightDp = 800)
        assertThat(reader.state.value.twoPages).isTrue()
        assertThat(reader.state.value.spreads.take(3)).containsExactly(Spread(0), Spread(1, 2), Spread(3, 4)).inOrder()
    }

    @Test
    fun `the setting forces two pages in landscape, or one page everywhere`() = runTest {
        val reader = ComicHarness(this).reader()
        reader.windowSize(widthDp = 891, heightDp = 411)

        reader.setTwoPages(TwoPages.Always)
        assertThat(reader.state.value.settings.twoPages).isEqualTo(TwoPages.Always)
        assertThat(reader.state.value.twoPages).isTrue()

        reader.windowSize(widthDp = 411, heightDp = 891)
        assertThat(reader.state.value.twoPages).isFalse()

        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.setTwoPages(TwoPages.Never)
        assertThat(reader.state.value.twoPages).isFalse()
        assertThat(reader.state.value.spreads).hasSize(Pages)
    }

    @Test
    fun `a window that changes keeps the reader on its page`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 7)
        val reader = harness.reader()
        assertThat(reader.state.value.spread).isEqualTo(Spread(6))

        reader.windowSize(widthDp = 1280, heightDp = 800)
        assertThat(reader.state.value.spread).isEqualTo(Spread(5, 6))
        assertThat(harness.pages.calls).contains("show 5")

        reader.windowSize(widthDp = 800, heightDp = 1280)
        assertThat(reader.state.value.spread).isEqualTo(Spread(6))
    }

    @Test
    fun `a window that changes writes no position`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 7)
        val reader = harness.reader()

        reader.windowSize(widthDp = 1280, heightDp = 800)
        advanceTimeBy(5.seconds)

        assertThat(harness.repository.savedPositions).isEmpty()
        assertThat(harness.outbox.positions).isEmpty()
    }

    @Test
    fun `a wide page arriving beside the page read is shown alone after it`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.next()
        reader.next()
        assertThat(reader.state.value.spread).isEqualTo(Spread(3, 4))

        harness.pages.ready(4, width = 2400, height = 1200)

        val state = reader.state.value
        assertThat(state.spread).isEqualTo(Spread(3))
        assertThat(state.spreads.subList(2, 5)).containsExactly(Spread(3), Spread(4), Spread(5, 6)).inOrder()
    }

    @Test
    fun `a wide page arriving before the page read leaves the reader on its page`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.next()
        reader.next()

        harness.pages.ready(2, width = 2400, height = 1200)

        val state = reader.state.value
        assertThat(state.spreads.take(4)).containsExactly(Spread(0), Spread(1), Spread(2), Spread(3, 4)).inOrder()
        assertThat(state.spread).isEqualTo(Spread(3, 4))
        assertThat(state.spreadIndex).isEqualTo(3)
    }

    @Test
    fun `an ordinary page arriving changes no spread`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        val before = reader.state.value.spreads

        harness.pages.ready(1)
        harness.pages.ready(2, width = 1200, height = 1200)

        assertThat(reader.state.value.spreads).isEqualTo(before)
    }

    @Test
    fun `the bar tells the page and how far it is in the album`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()

        assertThat(reader.state.value.label).isEqualTo(PageLabel(first = 12, last = null, count = Pages))
        assertThat(reader.state.value.percent).isEqualTo(25f)
    }

    @Test
    fun `with two pages the bar tells both, and the second one is read`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()

        reader.windowSize(widthDp = 1280, heightDp = 800)

        assertThat(reader.state.value.spread).isEqualTo(Spread(11, 12))
        assertThat(reader.state.value.label).isEqualTo(PageLabel(first = 12, last = 13, count = Pages))
        assertThat(reader.state.value.percent).isWithin(0.01f).of(13 * 100f / Pages)
    }

    @Test
    fun `an album that is not open has no label`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(0)

        val state = harness.reader().state.value

        assertThat(state.label).isNull()
        assertThat(state.percent).isEqualTo(0f)
    }
}
