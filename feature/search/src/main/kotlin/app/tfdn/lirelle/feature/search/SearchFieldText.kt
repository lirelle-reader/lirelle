package app.tfdn.lirelle.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * The text of the search field with its cursor.
 *
 * The screen state only holds the text. A text that comes from there without having been typed
 * (a recent search that was run, the field cleared) is shown with the cursor at its end, so
 * typing goes on after it. A text that was typed keeps the cursor where the user put it.
 */
internal class SearchFieldText(text: String) {
    var value by mutableStateOf(atEnd(text))
        private set

    /** The last text of the screen state that was looked at. */
    private var seen = text

    /** What was typed and has not come back from the screen state yet, oldest first. */
    private val typed = ArrayDeque<String>()

    /** The value to show when the screen state holds [text]. */
    fun showing(text: String): TextFieldValue {
        if (text != seen) {
            seen = text
            follow(text)
        }
        return value
    }

    private fun follow(text: String) {
        val echo = typed.indexOf(text)
        when {
            text == value.text -> typed.clear()
            // What was typed a moment ago comes back while newer keystrokes are on their way: the field is ahead.
            echo >= 0 -> repeat(echo + 1) { typed.removeFirst() }
            else -> {
                typed.clear()
                value = atEnd(text)
            }
        }
    }

    /** The user edited the field or moved the cursor; [onText] is told when the text changed. */
    fun onEdit(edited: TextFieldValue, onText: (String) -> Unit) {
        val changed = edited.text != value.text
        value = edited
        if (changed) {
            typed += edited.text
            onText(edited.text)
        }
    }
}
