package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Which pages are cut, when, and in which order. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagePanelsTest {
    private val bench = PanelsBench()

    @Test
    fun `nothing is cut while cutting is off`() = runTest(bench.dispatcher) {
        (0..2).forEach(bench::ready)
        val panels = bench.open(backgroundScope)

        panels.show(0, 3)
        advanceUntilIdle()

        assertThat(bench.cut).isEmpty()
        assertThat(panels.zones(0).value).isEqualTo(PageZones.Unknown)
        assertThat(bench.pages.calls).isEmpty()
    }

    @Test
    fun `the page shown is cut first, then the pages around it in the order they are fetched ahead`() =
        runTest(bench.dispatcher) {
            (0..9).forEach(bench::ready)
            val panels = bench.open(backgroundScope)

            panels.cutting(true)
            panels.show(4, 10)
            advanceUntilIdle()

            assertThat(bench.cutFiles)
                .containsExactlyElementsIn(listOf(4, 5, 6, 3, 7, 2, 8, 1, 9).map(bench::file))
                .inOrder()
            assertThat(bench.pages.calls).isEmpty()
        }

    @Test
    fun `a page whose picture is not on the device waits for it, and no fetch is asked`() =
        runTest(bench.dispatcher) {
            val panels = bench.open(backgroundScope)

            panels.cutting(true)
            panels.show(1, 2)
            advanceUntilIdle()

            assertThat(bench.cut).isEmpty()
            assertThat(panels.zones(1).value).isEqualTo(PageZones.Unknown)
            assertThat(bench.pages.calls).isEmpty()

            bench.ready(1)
            advanceUntilIdle()

            assertThat(panels.zones(1).value).isInstanceOf(PageZones.Cut::class.java)
            assertThat(bench.cutFiles).containsExactly(bench.file(1))
            assertThat(bench.pages.calls).isEmpty()
        }

    @Test
    fun `a page that arrives late is cut in its turn, the others not waiting for it`() = runTest(bench.dispatcher) {
        listOf(2, 3).forEach(bench::ready)
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(1, 4)
        advanceUntilIdle()

        assertThat(bench.cutFiles).containsExactly(bench.file(2), bench.file(3)).inOrder()

        bench.ready(1)
        advanceUntilIdle()

        assertThat(bench.cutFiles).containsExactly(bench.file(2), bench.file(3), bench.file(1)).inOrder()
    }

    @Test
    fun `another direction starts again`() = runTest(bench.dispatcher) {
        bench.ready(1)
        val panels = bench.open(backgroundScope)
        panels.cutting(true)
        panels.show(1, 2)
        advanceUntilIdle()
        assertThat(panels.zones(1).value).isInstanceOf(PageZones.Cut::class.java)
        val leftToRight = bench.store.of(bench.key, Direction.LeftToRight)
        val gate = CompletableDeferred<Unit>().also { bench.gate = it }

        panels.direction(Direction.RightToLeft)
        advanceUntilIdle()

        assertThat(panels.zones(1).value).isEqualTo(PageZones.Unknown)
        assertThat(bench.cut)
            .containsExactly(bench.file(1) to Direction.LeftToRight, bench.file(1) to Direction.RightToLeft)
            .inOrder()

        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(panels.zones(1).value).isInstanceOf(PageZones.Cut::class.java)
        assertThat(leftToRight).isNotEmpty()
        assertThat(bench.store.of(bench.key, Direction.LeftToRight)).isEqualTo(leftToRight)
        assertThat(bench.store.of(bench.key, Direction.RightToLeft)).isNotEmpty()
    }

    @Test
    fun `turning the cutting off stops after the page being cut`() = runTest(bench.dispatcher) {
        (0..4).forEach(bench::ready)
        val gate = CompletableDeferred<Unit>().also { bench.gate = it }
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(0, 5)
        runCurrent()
        panels.cutting(false)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(bench.cutFiles).containsExactly(bench.file(1))
        assertThat(panels.zones(1).value).isInstanceOf(PageZones.Cut::class.java)
        assertThat(panels.zones(2).value).isEqualTo(PageZones.Unknown)
    }

    @Test
    fun `moving on cuts around the new page first`() = runTest(bench.dispatcher) {
        (0..19).forEach(bench::ready)
        val gate = CompletableDeferred<Unit>().also { bench.gate = it }
        val panels = bench.open(backgroundScope)

        panels.cutting(true)
        panels.show(0, 20)
        runCurrent()
        panels.show(10, 20)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(bench.cutFiles.take(2)).containsExactly(bench.file(1), bench.file(10)).inOrder()
        // Then around the new page, and nothing more of the pages left behind.
        assertThat(bench.cutFiles.drop(1))
            .containsExactlyElementsIn(listOf(10, 11, 12, 9, 13, 8, 14, 7, 15, 6, 5).map(bench::file))
            .inOrder()
    }

    @Test
    fun `closing stops everything`() = runTest(bench.dispatcher) {
        val panels = bench.open(backgroundScope)
        panels.cutting(true)
        panels.show(1, 2)
        advanceUntilIdle()

        panels.close()
        bench.ready(1)
        advanceUntilIdle()

        assertThat(bench.cut).isEmpty()
        assertThat(panels.zones(1).value).isEqualTo(PageZones.Unknown)
    }

    @Test
    fun `closing during a cut stops there, and what that cut found is neither kept nor shown`() =
        runTest(bench.dispatcher) {
            (0..3).forEach(bench::ready)
            val panels = bench.open(backgroundScope)
            // The cut itself never waits, so the gate is of no use here: the reader closes from within the cut.
            bench.answer = {
                panels.close()
                CutOutcome.Zones(bench.zones)
            }

            panels.cutting(true)
            panels.show(0, 4)
            advanceUntilIdle()

            assertThat(bench.cutFiles).containsExactly(bench.file(1))
            assertThat(panels.zones(1).value).isEqualTo(PageZones.Unknown)
            assertThat(bench.store.of(bench.key, Direction.LeftToRight)).isEmpty()
        }

    @Test
    fun `the page shown whose picture arrives while another is cut is the next one cut`() =
        runTest(bench.dispatcher) {
            (2..5).forEach(bench::ready)
            val panels = bench.open(backgroundScope)
            // The picture arrives from within the cut, which never waits: whoever tells of it has to be let in.
            bench.answer = { file ->
                if (file == bench.file(2)) bench.ready(1)
                CutOutcome.Zones(bench.zones)
            }

            panels.cutting(true)
            panels.show(1, 6)
            advanceUntilIdle()

            assertThat(bench.cutFiles)
                .containsExactlyElementsIn(listOf(2, 1, 3, 4, 5).map(bench::file))
                .inOrder()
        }
}
