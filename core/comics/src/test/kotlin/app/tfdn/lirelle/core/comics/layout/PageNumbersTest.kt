package app.tfdn.lirelle.core.comics.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageNumbersTest {

    @Test
    fun `the server counts pages from 1`() {
        assertThat(pageNumberOf(0)).isEqualTo(1)
        assertThat(pageNumberOf(41)).isEqualTo(42)
    }

    @Test
    fun `a page number is the index after it`() {
        assertThat(pageIndexOf(1, pageCount = 10)).isEqualTo(0)
        assertThat(pageIndexOf(10, pageCount = 10)).isEqualTo(9)
    }

    @Test
    fun `no page number is the first page`() {
        assertThat(pageIndexOf(null, pageCount = 10)).isEqualTo(0)
    }

    @Test
    fun `a page number outside the album is brought back into it`() {
        assertThat(pageIndexOf(0, pageCount = 10)).isEqualTo(0)
        assertThat(pageIndexOf(-4, pageCount = 10)).isEqualTo(0)
        assertThat(pageIndexOf(11, pageCount = 10)).isEqualTo(9)
    }

    @Test
    fun `an album without a page has its first page only`() {
        assertThat(pageIndexOf(3, pageCount = 0)).isEqualTo(0)
        assertThat(pageIndexOf(null, pageCount = 0)).isEqualTo(0)
    }

    @Test
    fun `the percentage counts the page shown as read`() {
        assertThat(percentOf(0, pageCount = 4)).isEqualTo(25f)
        assertThat(percentOf(1, pageCount = 4)).isEqualTo(50f)
        assertThat(percentOf(3, pageCount = 4)).isEqualTo(100f)
        assertThat(percentOf(0, pageCount = 1)).isEqualTo(100f)
        assertThat(percentOf(0, pageCount = 3)).isWithin(0.001f).of(33.3333f)
    }

    @Test
    fun `the percentage of an album without a page is zero`() {
        assertThat(percentOf(0, pageCount = 0)).isEqualTo(0f)
    }

    @Test
    fun `the percentage stays between 0 and 100`() {
        assertThat(percentOf(-2, pageCount = 4)).isEqualTo(0f)
        assertThat(percentOf(9, pageCount = 4)).isEqualTo(100f)
    }
}
