package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A folder that cannot say where it is: every read and every write of a store on it throws. */
private class BrokenFolder(path: String) : File(path) {
    override fun getCanonicalPath(): String = error("no folder")
}

/** What the cuts leave alone, and what does not stop them: a store or a page that fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagePanelsSafeTest {
    private val bench = PanelsBench()

    private fun zonesOf(panels: PagePanels, index: Int): List<Zone> = (panels.zones(index).value as PageZones.Cut).zones

    @Test
    fun `the pages of a PDF album are never cut`() = runTest(bench.dispatcher) {
        bench.ready(0)
        val opener = DefaultPagePanelsOpener(bench.store, bench.dispatcher)
        val panels = opener.open(bench.key.copy(kind = PagedKind.Pdf), bench.pages, backgroundScope)

        panels.cutting(true)
        panels.direction(Direction.RightToLeft)
        panels.show(0, 1)
        advanceUntilIdle()
        panels.close()

        assertThat(panels.zones(0).value).isEqualTo(PageZones.Unknown)
        assertThat(bench.directory.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a PDF album says its pages are never cut, an album of pictures that they are`() = runTest(bench.dispatcher) {
        val opener = DefaultPagePanelsOpener(bench.store, bench.dispatcher)

        assertThat(opener.open(bench.key.copy(kind = PagedKind.Pdf), bench.pages, backgroundScope).cuts).isFalse()
        assertThat(opener.open(bench.key, bench.pages, backgroundScope).cuts).isTrue()
    }

    @Test
    fun `a store that cannot be read keeps nothing, and the pages are cut`() = runTest(bench.dispatcher) {
        (1..2).forEach(bench::ready)
        val panels = bench.open(backgroundScope, zoneStore = ZoneStore(BrokenFolder(bench.directory.path)))

        panels.cutting(true)
        panels.show(1, 3)
        advanceUntilIdle()

        assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2)).inOrder()
        assertThat(zonesOf(panels, 1)).isEqualTo(bench.zones)
        assertThat(zonesOf(panels, 2)).isEqualTo(bench.zones)
    }

    @Test
    fun `a store that cannot be written leaves the page cut, and the next pages are cut`() =
        runTest(bench.dispatcher) {
            (1..2).forEach(bench::ready)
            val store = ZoneStore(bench.directory, now = { error("no clock") })
            val panels = bench.open(backgroundScope, zoneStore = store)

            panels.cutting(true)
            panels.show(1, 3)
            advanceUntilIdle()

            assertThat(bench.cutFiles).containsExactly(bench.file(1), bench.file(2)).inOrder()
            assertThat(zonesOf(panels, 1)).isEqualTo(bench.zones)
            assertThat(zonesOf(panels, 2)).isEqualTo(bench.zones)
        }

    @Test
    fun `a page that fails to say what the device has of it is passed over, the others are cut`() =
        runTest(bench.dispatcher) {
            (1..3).forEach(bench::ready)
            val pages = object : ComicPages by bench.pages {
                override fun page(index: Int): Flow<PageState> =
                    if (index == 1) flow { error("no page") } else bench.pages.page(index)
            }
            val panels = bench.open(backgroundScope, watched = pages)

            panels.cutting(true)
            panels.show(1, 4)
            advanceUntilIdle()

            assertThat(bench.cutFiles).containsExactly(bench.file(2), bench.file(3)).inOrder()
            assertThat(panels.zones(1).value).isEqualTo(PageZones.Unknown)
        }
}
