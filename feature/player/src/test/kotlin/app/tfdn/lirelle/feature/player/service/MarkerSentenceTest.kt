package app.tfdn.lirelle.feature.player.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The sentence a page marker is in, told from the marker alone: section 1 has s1 to s3, section 3 s4 and s5. */
class MarkerSentenceTest {

    private val map = ReadAlongMaps.inventedMap()

    private fun fragmentAt(cfi: String) = map.sentenceAtMarker(cfi)?.fragment

    @Test
    fun `a marker inside a sentence names it - the page's first words, as the reader writes them`() {
        // Section 1 is the spine's second item: /6/4.
        assertThat(fragmentAt("epubcfi(/6/4!/4/2[p1]/4[s2]/1:12)")).isEqualTo("s2")
        // A range, as a page is: its start is where the marker is.
        assertThat(fragmentAt("epubcfi(/6/4!/4/2[p1],/2[s1]/1:0,/6[s3]/1:40)")).isEqualTo("s1")
        assertThat(fragmentAt("epubcfi(/6/8[three]!/4/6/2[s5]/3:2)")).isEqualTo("s5")
    }

    @Test
    fun `the innermost sentence is the one, and an id that is no sentence is passed over`() {
        assertThat(fragmentAt("epubcfi(/6/4!/4[body]/2[s1]/2[note]/1:0)")).isEqualTo("s1")
        assertThat(fragmentAt("epubcfi(/6/4!/4/2[s1]/2[s2]/1:0)")).isEqualTo("s2")
    }

    @Test
    fun `an id is read as it was written - escaped characters, parameters and the words of a text left out`() {
        val escaped = ReadAlongMaps.inventedMap()
        assertThat(escaped.sentenceAtMarker("epubcfi(/6/4!/4/2[s3;s=b]/1:4[wor,ds])")?.fragment).isEqualTo("s3")
        // The words quoted after a character offset are not an id, even when they spell one.
        assertThat(fragmentAt("epubcfi(/6/4!/4/2/1:4[s2])")).isNull()
        assertThat(markerIds("epubcfi(/6/4!/4/2[a^]b^,c]/1:0)")).containsExactly("a]b,c")
    }

    @Test
    fun `a marker in a section without narration is at the first sentence of the next narrated one`() {
        // Section 2 (/6/6) has none; the cover (/6/2) neither.
        assertThat(fragmentAt("epubcfi(/6/6!/4/2/1:0)")).isEqualTo("s4")
        assertThat(fragmentAt("epubcfi(/6/2!/4/2/1:0)")).isEqualTo("s1")
    }

    @Test
    fun `a marker between the sentences of a narrated section names none - its place is in the page, not in it`() {
        assertThat(fragmentAt("epubcfi(/6/4!/4/2[p1]/3:1)")).isNull()
        assertThat(fragmentAt("epubcfi(/6/4!/4/8/1:0)")).isNull()
    }

    @Test
    fun `a marker past the narration, or that is not one, names none`() {
        assertThat(fragmentAt("epubcfi(/6/10!/4/2/1:0)")).isNull()
        assertThat(fragmentAt("not a marker")).isNull()
        assertThat(fragmentAt("epubcfi(/4/2)")).isNull()
    }
}
