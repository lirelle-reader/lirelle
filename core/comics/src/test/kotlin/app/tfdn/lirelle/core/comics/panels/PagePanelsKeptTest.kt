package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What the cuts take from the store of zones and leave there, and what comes of a cut that fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagePanelsKeptTest {
    private val bench = PanelsBench()
    private val found = CutOutcome.Zones(bench.zones)
    private val other = listOf(Zone(0f, 0f, 1f, 0.5f, ZoneKind.Filled), Zone(0f, 0.5f, 1f, 1f, ZoneKind.Filled))
    private val failed = PageZones.Whole(WholePage.Failed)
    private val noPanels = PageZones.Whole(WholePage.NoPanels)

    /**
     * The pages after the cover, up to [last], are on the device, the cutting is on and the reader on the first of
     * them: all are cut.
     */
    private fun TestScope.cutAll(last: Int = 1): PagePanels {
        (1..last).forEach(bench::ready)
        val panels = bench.open(backgroundScope)
        panels.cutting(true)
        panels.show(1, last + 1)
        advanceUntilIdle()
        return panels
    }

    private fun kept() = ZoneStore(bench.directory).of(bench.key, Direction.LeftToRight)

    @Test
    fun `a page already kept is not cut again`() = runTest(bench.dispatcher) {
        bench.ready(1)
        bench.store.keep(bench.key, Direction.LeftToRight, 1, pictureBytes = bench.file(1).length(), other)

        val panels = cutAll()

        assertThat(bench.cut).isEmpty()
        assertThat(panels.zones(1).value).isEqualTo(PageZones.Cut(other, millis = 0))
    }

    @Test
    fun `zones kept for a picture of another length are not used`() = runTest(bench.dispatcher) {
        bench.ready(1)
        bench.store.keep(bench.key, Direction.LeftToRight, 1, pictureBytes = bench.file(1).length() + 1, other)

        val panels = cutAll()

        assertThat(bench.cutFiles).containsExactly(bench.file(1))
        assertThat((panels.zones(1).value as PageZones.Cut).zones).isEqualTo(bench.zones)
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), bench.zones))
    }

    @Test
    fun `what is cut is kept`() = runTest(bench.dispatcher) {
        cutAll()

        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), bench.zones))
    }

    @Test
    fun `a file that is no picture is kept as not cut`() = runTest(bench.dispatcher) {
        bench.answer = { CutOutcome.NotCut }

        val panels = cutAll()

        assertThat(panels.zones(1).value).isEqualTo(PageZones.Whole(WholePage.NoPicture))
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), emptyList()))
    }

    @Test
    fun `a page cut into two zones or more is cut, and its time is told`() = runTest(bench.dispatcher) {
        val panels = cutAll()

        val cut = panels.zones(1).value as PageZones.Cut
        assertThat(cut.zones).isEqualTo(bench.zones)
        assertThat(cut.millis).isAtLeast(0)
    }

    @Test
    fun `a page whose cut gives no zone is read whole for want of panels, and kept`() = runTest(bench.dispatcher) {
        bench.answer = { CutOutcome.Zones(emptyList()) }

        val panels = cutAll()

        assertThat(panels.zones(1).value).isEqualTo(noPanels)
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), emptyList()))
    }

    @Test
    fun `a page whose cut gives one zone is read whole for want of panels, and kept`() = runTest(bench.dispatcher) {
        bench.answer = { CutOutcome.Zones(other.take(1)) }

        val panels = cutAll()

        assertThat(panels.zones(1).value).isEqualTo(noPanels)
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), other.take(1)))
    }

    @Test
    fun `a page kept with no zone, or with one, is read whole for want of panels and not cut again`() =
        runTest(bench.dispatcher) {
            (1..2).forEach(bench::ready)
            bench.store.keep(bench.key, Direction.LeftToRight, 1, bench.file(1).length(), emptyList())
            bench.store.keep(bench.key, Direction.LeftToRight, 2, bench.file(2).length(), other.take(1))

            val panels = cutAll(last = 2)

            assertThat(bench.cut).isEmpty()
            assertThat(panels.zones(1).value).isEqualTo(noPanels)
            assertThat(panels.zones(2).value).isEqualTo(noPanels)
        }

    @Test
    fun `a page there was no room for is whole as failed, and not kept`() = runTest(bench.dispatcher) {
        bench.answer = { file -> if (file == bench.file(1)) CutOutcome.Later else found }

        val panels = cutAll(last = 2)

        assertThat(panels.zones(1).value).isEqualTo(failed)
        assertThat((panels.zones(2).value as PageZones.Cut).zones).isEqualTo(bench.zones)
        // Once: the page is not tried in a loop.
        assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2)).inOrder()
        assertThat(kept().keys).containsExactly(2)
    }

    @Test
    fun `a page there was no room for is cut the next time the album is read`() = runTest(bench.dispatcher) {
        bench.answer = { CutOutcome.Later }
        cutAll().close()
        bench.answer = { found }

        val panels = cutAll()

        assertThat((panels.zones(1).value as PageZones.Cut).zones).isEqualTo(bench.zones)
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), bench.zones))
    }

    @Test
    fun `a cut that throws leaves its page whole as failed, is not kept, and the next pages are cut`() =
        runTest(bench.dispatcher) {
            bench.answer = { file -> if (file == bench.file(1)) error("no cut") else found }

            val panels = cutAll(last = 3)

            assertThat(panels.zones(1).value).isEqualTo(failed)
            assertThat((panels.zones(2).value as PageZones.Cut).zones).isEqualTo(bench.zones)
            // Once each: the page that failed is not tried in a loop.
            assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2), bench.file(3)).inOrder()
            assertThat(kept().keys).containsExactly(2, 3)
        }

    @Test
    fun `a cut the memory is too short for leaves its page whole as failed, not kept`() = runTest(bench.dispatcher) {
        bench.answer = { file -> if (file == bench.file(1)) throw OutOfMemoryError() else found }

        val panels = cutAll(last = 2)

        assertThat(panels.zones(1).value).isEqualTo(failed)
        assertThat((panels.zones(2).value as PageZones.Cut).zones).isEqualTo(bench.zones)
        assertThat(kept().keys).containsExactly(2)
    }

    @Test
    fun `a page whose cut threw is tried again the next time the album is read`() = runTest(bench.dispatcher) {
        bench.answer = { error("no cut") }
        cutAll().close()
        bench.answer = { found }

        val panels = cutAll()

        assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(1))
        assertThat((panels.zones(1).value as PageZones.Cut).zones).isEqualTo(bench.zones)
        assertThat(kept()[1]).isEqualTo(KeptZones(bench.file(1).length(), bench.zones))
    }
}
