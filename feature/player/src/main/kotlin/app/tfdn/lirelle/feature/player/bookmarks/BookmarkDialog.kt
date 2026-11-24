package app.tfdn.lirelle.feature.player.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.controller.PlayerUiState

internal const val BookmarkTitleFieldTag = "player-bookmark-title"
internal const val BookmarkNoteFieldTag = "player-bookmark-note"

/** The longest title the server keeps. */
internal const val TitleMax = 500

/** The longest note the server keeps. */
internal const val NoteMax = 4_000

private const val NoteMinLines = 3
private const val NoteMaxLines = 8

/** The dialog of the bookmark being edited ([PlayerBookmarks.editing]), while it is in the list; [actions] saves it. */
@Composable
internal fun BookmarkEditing(state: PlayerUiState, bookmarks: PlayerBookmarks, actions: BookmarkActions) {
    val bookmark = bookmarks.editing?.let { id -> state.bookmarks.firstOrNull { it.id == id } } ?: return
    // Another bookmark is another dialog, with its own fields.
    key(bookmark.id) {
        BookmarkDialog(
            bookmark = bookmark,
            place = BookmarkPlace.of(bookmark.positionMs, state.chapters).text,
            onSave = { title, note ->
                actions.editBookmark(bookmark, title, note)
                bookmarks.editing = null
            },
            onDismiss = { bookmarks.editing = null },
        )
    }
}

/**
 * "Edit the bookmark": where it is, its title (needed, [TitleMax] characters at most) and its note (optional,
 * [NoteMax] at most), each with its label above it. What is typed or pasted past the limits is left out.
 */
@Composable
private fun BookmarkDialog(
    bookmark: AudioBookmark,
    place: String,
    onSave: (title: String, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = rememberTextFieldState(bookmark.title)
    val note = rememberTextFieldState(bookmark.note.orEmpty())
    val blank = title.text.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_bookmark_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(place, style = MaterialTheme.typography.bodyMedium)
                val needed = stringResource(R.string.player_bookmark_title_needed)
                OutlinedTextField(
                    state = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BookmarkTitleFieldTag)
                        .semantics { if (blank) error(needed) },
                    labelPosition = TextFieldLabelPosition.Above(),
                    label = { Text(stringResource(R.string.player_bookmark_title)) },
                    inputTransformation = KeptUnder(TitleMax),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    isError = blank,
                    supportingText = if (blank) ({ Text(needed) }) else null,
                )
                OutlinedTextField(
                    state = note,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BookmarkNoteFieldTag),
                    labelPosition = TextFieldLabelPosition.Above(),
                    label = { Text(stringResource(R.string.player_bookmark_note)) },
                    inputTransformation = KeptUnder(NoteMax),
                    lineLimits = TextFieldLineLimits.MultiLine(NoteMinLines, NoteMaxLines),
                    placeholder = { Text(stringResource(R.string.player_bookmark_note_hint)) },
                )
            }
        },
        confirmButton = {
            val save = { onSave(title.text.trim().toString(), note.text.trim().toString().ifEmpty { null }) }
            TextButton(onClick = save, enabled = !blank) {
                SingleLineLabel(stringResource(R.string.player_bookmark_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                SingleLineLabel(stringResource(R.string.player_bookmark_cancel))
            }
        },
    )
}

/**
 * What the user types or pastes past [max] characters is left out of what was typed, never out of the text
 * already there, and never half a character (a surrogate pair stays whole). An edit that cannot be shortened so
 * (several changes at once) is refused.
 */
private class KeptUnder(private val max: Int) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val over = length - max
        if (over <= 0) return
        // What was typed: between what is the same as before at the start and at the end.
        val before = originalText
        var start = 0
        while (start < before.length && start < length && before[start] == charAt(start)) start++
        var kept = 0
        while (kept < before.length - start && kept < length - start &&
            before[before.length - 1 - kept] == charAt(length - 1 - kept)
        ) {
            kept++
        }
        val end = length - kept
        if (end - start < over) {
            revertAllChanges()
            return
        }
        var cut = end - over
        if (cut > start && Character.isHighSurrogate(charAt(cut - 1))) cut--
        delete(cut, end)
    }
}
