package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The first page of an album is its cover: it is told read whole, as a cover, and never cut. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagePanelsCoverTest {
    private val bench = PanelsBench()
    private val notCut = PageZones.Whole(WholePage.Cover)

    @Test
    fun `the first page of an album is not cut`() = runTest(bench.dispatcher) {
        (0..3).forEach(bench::ready)
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(0, 4)
        advanceUntilIdle()

        assertThat(panels.zones(0).value).isEqualTo(notCut)
        // The others in their order, as if the first had been cut before them.
        assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2), bench.file(3)).inOrder()
        assertThat(bench.store.of(bench.key, Direction.LeftToRight).keys).containsExactly(1, 2, 3)
    }

    @Test
    fun `zones kept of the first page from before are not used`() = runTest(bench.dispatcher) {
        bench.ready(0)
        bench.store.keep(bench.key, Direction.LeftToRight, 0, pictureBytes = bench.file(0).length(), bench.zones)
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(0, 1)
        advanceUntilIdle()

        assertThat(panels.zones(0).value).isEqualTo(notCut)
        assertThat(bench.cut).isEmpty()
    }

    @Test
    fun `the first page is told not cut even when its picture is not on the device`() = runTest(bench.dispatcher) {
        listOf(1, 2).forEach(bench::ready)
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(1, 3)
        advanceUntilIdle()

        assertThat(panels.zones(0).value).isEqualTo(notCut)
        assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2)).inOrder()
        assertThat(bench.pages.calls).isEmpty()

        // Its picture coming changes nothing.
        bench.ready(0)
        advanceUntilIdle()

        assertThat(panels.zones(0).value).isEqualTo(notCut)
        assertThat(bench.cutFiles).doesNotContain(bench.file(0))
    }

    @Test
    fun `the first page is told nothing of while the cutting is off, nor while it is out of the window`() =
        runTest(bench.dispatcher) {
            (0..19).forEach(bench::ready)
            val panels = bench.open(backgroundScope)

            panels.show(0, 20)
            advanceUntilIdle()
            assertThat(panels.zones(0).value).isEqualTo(PageZones.Unknown)

            panels.show(15, 20)
            panels.cutting(true)
            advanceUntilIdle()
            assertThat(panels.zones(0).value).isEqualTo(PageZones.Unknown)

            panels.show(0, 20)
            advanceUntilIdle()
            assertThat(panels.zones(0).value).isEqualTo(notCut)
        }

    @Test
    fun `read the other way, the first page is still told not cut`() = runTest(bench.dispatcher) {
        val panels = bench.open(backgroundScope)
        panels.cutting(true)
        panels.show(0, 1)
        advanceUntilIdle()

        panels.direction(Direction.RightToLeft)
        advanceUntilIdle()

        assertThat(panels.zones(0).value).isEqualTo(notCut)
        assertThat(bench.cut).isEmpty()
    }
}
