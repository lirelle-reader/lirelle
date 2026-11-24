package app.tfdn.lirelle.core.reader.marks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightColourTest {

    @Test
    fun `a six-digit colour is upper-cased`() {
        assertThat(HighlightColour.display("#facc15")).isEqualTo("#FACC15")
        assertThat(HighlightColour.display("#12ab9F")).isEqualTo("#12AB9F")
        assertThat(HighlightColour.display("#4ADE80")).isEqualTo("#4ADE80")
    }

    @Test
    fun `anything else is drawn yellow`() {
        for (stored in listOf("yellow", "", "#FFF", "rgb(250, 204, 21)", "#FACC15 ", "FACC15", "#GGGGGG")) {
            assertThat(HighlightColour.display(stored)).isEqualTo("#FACC15")
        }
    }

    @Test
    fun `the four colours of the app`() {
        assertThat(HighlightColour.entries.map { it.hex })
            .containsExactly("#FACC15", "#4ADE80", "#38BDF8", "#F472B6").inOrder()
    }
}
