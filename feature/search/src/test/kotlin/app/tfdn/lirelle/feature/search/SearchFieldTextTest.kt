package app.tfdn.lirelle.feature.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchFieldTextTest {
    private val reported = mutableListOf<String>()

    private fun SearchFieldText.type(text: String, cursor: Int) =
        onEdit(TextFieldValue(text, TextRange(cursor))) { reported += it }

    @Test
    fun `a text that comes from outside has the cursor at its end`() {
        val field = SearchFieldText("")

        assertThat(field.showing("mars").selection).isEqualTo(TextRange(4))
        assertThat(field.showing("").text).isEmpty()
    }

    @Test
    fun `a text that was typed keeps its cursor when it comes back`() {
        val field = SearchFieldText("mars")

        field.type("mxars", cursor = 2)

        assertThat(reported).containsExactly("mxars")
        assertThat(field.showing("mxars").selection).isEqualTo(TextRange(2))
    }

    @Test
    fun `moving the cursor reports nothing`() {
        val field = SearchFieldText("mars")

        field.type("mars", cursor = 1)

        assertThat(reported).isEmpty()
        assertThat(field.showing("mars").selection).isEqualTo(TextRange(1))
    }

    @Test
    fun `the field stays ahead of a screen state that is late`() {
        val field = SearchFieldText("")
        field.type("m", cursor = 1)
        field.type("am", cursor = 1)
        field.type("abm", cursor = 2)

        // Not told yet, then told of each keystroke after the next one was typed.
        assertThat(field.showing("").text).isEqualTo("abm")
        assertThat(field.showing("m").text).isEqualTo("abm")
        assertThat(field.showing("am").text).isEqualTo("abm")
        assertThat(field.showing("abm").selection).isEqualTo(TextRange(2))
    }

    @Test
    fun `a text from outside replaces what was typed`() {
        val field = SearchFieldText("")
        field.type("ma", cursor = 1)

        val shown = field.showing("venus")

        assertThat(shown.text).isEqualTo("venus")
        assertThat(shown.selection).isEqualTo(TextRange(5))
    }
}
