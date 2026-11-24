package app.tfdn.lirelle.core.comics.layout

import app.tfdn.lirelle.core.comics.settings.TwoPages
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpreadsTest {

    @Test
    fun `automatic shows two pages on a tablet in landscape only`() {
        assertThat(showsTwoPages(TwoPages.Automatic, widthDp = 1280, heightDp = 800)).isTrue()
        assertThat(showsTwoPages(TwoPages.Automatic, widthDp = 960, heightDp = 600)).isTrue()
        assertThat(showsTwoPages(TwoPages.Automatic, widthDp = 960, heightDp = 599)).isFalse()
        assertThat(showsTwoPages(TwoPages.Automatic, widthDp = 800, heightDp = 1280)).isFalse()
        assertThat(showsTwoPages(TwoPages.Automatic, widthDp = 700, heightDp = 700)).isFalse()
    }

    @Test
    fun `always shows two pages in any window wider than high`() {
        assertThat(showsTwoPages(TwoPages.Always, widthDp = 800, heightDp = 360)).isTrue()
        assertThat(showsTwoPages(TwoPages.Always, widthDp = 360, heightDp = 800)).isFalse()
        assertThat(showsTwoPages(TwoPages.Always, widthDp = 700, heightDp = 700)).isFalse()
    }

    @Test
    fun `never shows one page whatever the window`() {
        assertThat(showsTwoPages(TwoPages.Never, widthDp = 1280, heightDp = 800)).isFalse()
        assertThat(showsTwoPages(TwoPages.Never, widthDp = 800, heightDp = 1280)).isFalse()
    }

    @Test
    fun `an album without a page has no spread`() {
        assertThat(spreads(0, emptySet(), twoPages = false)).isEmpty()
        assertThat(spreads(0, emptySet(), twoPages = true)).isEmpty()
        assertThat(spreads(-1, emptySet(), twoPages = true)).isEmpty()
    }

    @Test
    fun `an album of one page has one spread`() {
        assertThat(spreads(1, emptySet(), twoPages = false)).containsExactly(Spread(0))
        assertThat(spreads(1, emptySet(), twoPages = true)).containsExactly(Spread(0))
    }

    @Test
    fun `without two pages every page is a spread, the wide ones too`() {
        assertThat(spreads(4, setOf(2), twoPages = false))
            .containsExactly(Spread(0), Spread(1), Spread(2), Spread(3)).inOrder()
    }

    @Test
    fun `the cover is alone, then pages go by pairs`() {
        assertThat(spreads(5, emptySet(), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3, 4)).inOrder()
    }

    @Test
    fun `a last page without a partner is alone`() {
        assertThat(spreads(2, emptySet(), twoPages = true)).containsExactly(Spread(0), Spread(1)).inOrder()
        assertThat(spreads(6, emptySet(), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3, 4), Spread(5)).inOrder()
    }

    @Test
    fun `a wide cover changes nothing`() {
        assertThat(spreads(3, setOf(0), twoPages = true)).containsExactly(Spread(0), Spread(1, 2)).inOrder()
    }

    @Test
    fun `a wide page right after the cover is alone and the pairs go on after it`() {
        assertThat(spreads(6, setOf(1), twoPages = true))
            .containsExactly(Spread(0), Spread(1), Spread(2, 3), Spread(4, 5)).inOrder()
    }

    @Test
    fun `a wide page in the middle is alone and the pairs go on after it`() {
        assertThat(spreads(7, setOf(3), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3), Spread(4, 5), Spread(6)).inOrder()
    }

    @Test
    fun `a page before a wide one is alone rather than paired with it`() {
        assertThat(spreads(6, setOf(2), twoPages = true))
            .containsExactly(Spread(0), Spread(1), Spread(2), Spread(3, 4), Spread(5)).inOrder()
    }

    @Test
    fun `consecutive wide pages are each alone`() {
        assertThat(spreads(7, setOf(3, 4), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3), Spread(4), Spread(5, 6)).inOrder()
    }

    @Test
    fun `a wide last page is alone`() {
        assertThat(spreads(4, setOf(3), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3)).inOrder()
        assertThat(spreads(5, setOf(4), twoPages = true))
            .containsExactly(Spread(0), Spread(1, 2), Spread(3), Spread(4)).inOrder()
    }

    @Test
    fun `wide pages outside the album are ignored`() {
        assertThat(spreads(3, setOf(-1, 3, 9), twoPages = true)).containsExactly(Spread(0), Spread(1, 2)).inOrder()
    }

    @Test
    fun `every page is in exactly one spread, in order`() {
        val pages = spreads(40, setOf(0, 5, 6, 17, 39), twoPages = true).flatMap { it.pages }

        assertThat(pages).isEqualTo((0 until 40).toList())
    }

    @Test
    fun `the spread of a page is the one that holds it`() {
        val spreads = spreads(7, setOf(3), twoPages = true)

        assertThat((0 until 7).map { spreadIndexOf(spreads, it) }).containsExactly(0, 1, 1, 2, 3, 3, 4).inOrder()
    }

    @Test
    fun `the spread of a page outside the album is the nearest one`() {
        val spreads = spreads(5, emptySet(), twoPages = true)

        assertThat(spreadIndexOf(spreads, -3)).isEqualTo(0)
        assertThat(spreadIndexOf(spreads, 12)).isEqualTo(2)
        assertThat(spreadIndexOf(emptyList(), 4)).isEqualTo(0)
    }
}
