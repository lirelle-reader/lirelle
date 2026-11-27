package app.tfdn.lirelle.core.reader.marks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CfiOrderTest {

    @Test
    fun `positions are in reading order by their numeric steps`() {
        val cfis = listOf("epubcfi(/6/8!/4/2/1:0)", "epubcfi(/6/4!/4/10/1:0)", "epubcfi(/6/4!/4/2/1:5)")

        assertThat(cfis.sortedWith(CfiOrder))
            .containsExactly("epubcfi(/6/4!/4/2/1:5)", "epubcfi(/6/4!/4/10/1:0)", "epubcfi(/6/8!/4/2/1:0)")
            .inOrder()
    }

    @Test
    fun `offsets and deeper steps count`() {
        assertThat(CfiOrder.compare("epubcfi(/6/4!/4/2/1:5)", "epubcfi(/6/4!/4/2/1:12)")).isLessThan(0)
        assertThat(CfiOrder.compare("epubcfi(/6/4!/4/2)", "epubcfi(/6/4!/4/2/1:0)")).isLessThan(0)
        assertThat(CfiOrder.compare("epubcfi(/6/4!/4/2/1:5)", "epubcfi(/6/4!/4/2/1:5)")).isEqualTo(0)
    }

    @Test
    fun `a range is placed by its start`() {
        val range = "epubcfi(/6/4!/4/2,/1:5,/3:2)"

        assertThat(CfiOrder.compare(range, "epubcfi(/6/4!/4/2/1:6)")).isLessThan(0)
        assertThat(CfiOrder.compare(range, "epubcfi(/6/4!/4/2/1:4)")).isGreaterThan(0)
        assertThat(CfiOrder.compare(range, "epubcfi(/6/4!/4/2/1:5)")).isEqualTo(0)
    }

    @Test
    fun `assertions in brackets are ignored`() {
        assertThat(CfiOrder.compare("epubcfi(/6/4[ch1]!/4[body]/2/1:5)", "epubcfi(/6/4!/4/2/1:5)")).isEqualTo(0)
    }

    @Test
    fun `malformed and null positions come last`() {
        val cfis = listOf(null, "not a cfi", "epubcfi(/6/8!/4/2)", "epubcfi()", "epubcfi(/6/4!/4/2)")

        assertThat(cfis.sortedWith(CfiOrder).take(2))
            .containsExactly("epubcfi(/6/4!/4/2)", "epubcfi(/6/8!/4/2)").inOrder()
        assertThat(CfiOrder.compare(null, null)).isEqualTo(0)
        assertThat(CfiOrder.compare("epubcfi(/6/4!/4/2)", null)).isLessThan(0)
        assertThat(CfiOrder.compare(null, "epubcfi(/6/4!/4/2)")).isGreaterThan(0)
    }

    @Test
    fun `the spine index is read from the first step after the package`() {
        assertThat(spineIndexOf("epubcfi(/6/14!/4/2)")).isEqualTo(6)
        assertThat(spineIndexOf("epubcfi(/6/2!/4/2/1:0)")).isEqualTo(0)
        assertThat(spineIndexOf("epubcfi(/6/4[ch1]!/4,/1:0,/1:5)")).isEqualTo(1)
    }

    @Test
    fun `no spine index without one`() {
        for (cfi in listOf("", "epubcfi(/6/3!/4/2)", "epubcfi(/4/14!/4/2)", "epubcfi(/6/14)", "garbage")) {
            assertThat(spineIndexOf(cfi)).isNull()
        }
    }

    @Test
    fun `a section starts at its body`() {
        assertThat(sectionStartCfi(0)).isEqualTo("epubcfi(/6/2!/4)")
        assertThat(sectionStartCfi(6)).isEqualTo("epubcfi(/6/14!/4)")
        assertThat(spineIndexOf(sectionStartCfi(30))).isEqualTo(30)
    }
}
