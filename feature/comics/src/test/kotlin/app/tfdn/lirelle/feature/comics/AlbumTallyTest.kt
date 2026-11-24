package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private val Cut = PageZones.Cut(
    listOf(Zone(0.1f, 0.1f, 0.9f, 0.4f, ZoneKind.Filled), Zone(0.1f, 0.5f, 0.9f, 0.9f, ZoneKind.Filled)),
    millis = 0,
)
private val NotCut = PageZones.Whole(WholePage.NoPanels)

/** The count of the pages read panel by panel that says an album lends itself badly to it. */
class AlbumTallyTest {

    private val tally = AlbumTally()

    /** Sees the pages from [first] on, one for each of [zones]; says for each what the tally said. */
    private fun see(vararg zones: PageZones?, first: Int = 0, tally: AlbumTally = this.tally): List<Boolean> =
        zones.mapIndexed { index, known -> tally.see(first + index, known) }

    @Test
    fun `six pages of which none is cut say so, at the sixth`() {
        assertThat(see(NotCut, NotCut, NotCut, NotCut, NotCut, NotCut))
            .containsExactly(false, false, false, false, false, true).inOrder()
    }

    @Test
    fun `six pages of which one is cut say so too`() {
        assertThat(see(NotCut, NotCut, Cut, NotCut, NotCut, NotCut).last()).isTrue()
        assertThat(see(NotCut, NotCut, NotCut, NotCut, NotCut, Cut, tally = AlbumTally()))
            .containsExactly(false, false, false, false, false, true).inOrder()
    }

    @Test
    fun `it is said once`() {
        see(NotCut, NotCut, NotCut, NotCut, NotCut, NotCut)

        assertThat(see(NotCut, NotCut, NotCut, NotCut, NotCut, NotCut, first = 6)).doesNotContain(true)
    }

    @Test
    fun `two cut pages never say so, however many follow that are not`() {
        assertThat(see(NotCut, Cut, NotCut, Cut, NotCut, NotCut, NotCut, NotCut, NotCut, NotCut, NotCut))
            .doesNotContain(true)
    }

    @Test
    fun `a cover, a page without panels and a file that is no picture count as not cut`() {
        val whole = listOf(WholePage.Cover, WholePage.NoPanels, WholePage.NoPicture).map { PageZones.Whole(it) }

        assertThat(see(*(whole + whole).toTypedArray()).last()).isTrue()
    }

    @Test
    fun `a cut that failed is not counted, nor a page of which nothing is known`() {
        val failed = PageZones.Whole(WholePage.Failed)

        assertThat(see(NotCut, failed, NotCut, PageZones.Unknown, NotCut, null, NotCut, NotCut))
            .doesNotContain(true)
        // The sixth page counted, the ninth seen.
        assertThat(tally.see(8, NotCut)).isTrue()
    }

    @Test
    fun `a page seen twice counts once`() {
        repeat(4) { assertThat(see(NotCut, NotCut, NotCut)).doesNotContain(true) }

        assertThat(see(NotCut, NotCut, NotCut, first = 3)).containsExactly(false, false, true).inOrder()
    }

    @Test
    fun `a page not known when it was first seen counts once it is`() {
        see(NotCut, NotCut, NotCut, NotCut, NotCut, null)

        assertThat(tally.see(5, PageZones.Whole(WholePage.Failed))).isFalse()
        assertThat(tally.see(5, NotCut)).isTrue()
    }

    @Test
    fun `a page counted as cut is not counted again`() {
        repeat(3) { see(Cut) }

        assertThat(see(NotCut, NotCut, NotCut, NotCut, NotCut, first = 1).last()).isTrue()
    }
}
