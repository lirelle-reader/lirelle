package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.feature.reader.text.TextAction
import java.text.NumberFormat

internal const val HighlightPanelTag = "reader-highlight-panel"
internal const val NoteFieldTag = "reader-note-field"

/** From this length on, the note field says how much of its limit is used. */
private const val NoteCountFrom = 3_500

private const val QuoteLines = 3
private const val NoteMinLines = 3
private const val NoteMaxLines = 6

private val PanelMaxWidth = 640.dp
private val PanelCorner = 24.dp
private val Gutter = 16.dp

/**
 * The panel of one highlight over the bottom of the page: its text, its colour, its note, Copy and
 * Delete. The page stays visible above it; a tap there closes the panel, asking first whether to
 * discard a note that was changed. Give it the whole of the reader's area.
 */
@Composable
internal fun HighlightPanel(panel: HighlightPanelUi, actions: ReaderActions, modifier: Modifier = Modifier) {
    val note = rememberNote(panel, actions)
    val pane = stringResource(R.string.reader_highlight_pane)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        // Behind the panel, not around it: a tap on the panel itself must not close it.
        Box(Modifier.matchParentSize().pointerInput(actions) { detectTapGestures { actions.closeHighlightPanel() } })
        Surface(
            modifier = Modifier
                .testTag(HighlightPanelTag)
                .semantics { paneTitle = pane }
                .widthIn(max = PanelMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * MaxSheetFraction),
            shape = RoundedCornerShape(topStart = PanelCorner, topEnd = PanelCorner),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Handle(Modifier.align(Alignment.CenterHorizontally))
                Quote(panel.text, panel.color)
                ColourDots(panel.color, actions::setHighlightColour, choice = true)
                NoteField(note, panel)
                PanelButtons(changed = note.text.trim() != panel.note.trim(), actions)
            }
        }
    }
    if (panel.confirmDiscard) DiscardDialog(actions)
}

/** Only a sign that the panel is a sheet: closing it is done above it or with the system's back. */
@Composable
private fun Handle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
    )
}

/** The highlighted text, three lines at most, beside a stroke of its colour. */
@Composable
private fun Quote(text: String, color: String) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Color(android.graphics.Color.parseColor(HighlightColour.display(color)))),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
            ),
            maxLines = QuoteLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The note field's own text, kept here while it is typed (an input method composing a word, an
 * accent being made, is never interrupted by the reader's state coming back a frame later) and
 * handed to the reader as it changes. It starts from the panel's draft, once per highlight, and
 * survives the screen being made again.
 */
@Composable
private fun rememberNote(panel: HighlightPanelUi, actions: ReaderActions): TextFieldState {
    val note = rememberSaveable(panel.id, saver = TextFieldState.Saver) { TextFieldState(panel.draft) }
    val draft by rememberUpdatedState(panel.draft)
    val reader by rememberUpdatedState(actions)
    LaunchedEffect(note) {
        snapshotFlow { note.text.toString() }.collect { if (it != draft) reader.editNote(it) }
    }
    return note
}

/** The note as typed; an edit past the limit is refused whole. Its count shows once it nears the limit. */
@Composable
private fun NoteField(note: TextFieldState, panel: HighlightPanelUi) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(panel.id, panel.focusNote) { if (panel.focusNote) focus.requestFocus() }
    val numbers = NumberFormat.getInstance(LocalConfiguration.current.locales[0])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.reader_highlight_note),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
        )
        val length = note.text.length
        OutlinedTextField(
            state = note,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(NoteFieldTag),
            inputTransformation = InputTransformation.maxLength(NoteMaxLength),
            lineLimits = TextFieldLineLimits.MultiLine(NoteMinLines, NoteMaxLines),
            supportingText = if (length >= NoteCountFrom) {
                {
                    Text(
                        stringResource(
                            R.string.reader_highlight_note_count,
                            numbers.format(length),
                            numbers.format(NoteMaxLength),
                        ),
                    )
                }
            } else {
                null
            },
        )
    }
}

/**
 * Copy and Delete, then Save, enabled once the note differs from the one saved, which closes the panel and
 * its keyboard; whole buttons wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelButtons(changed: Boolean, actions: ReaderActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
    ) {
        FlowRow {
            TextButton(onClick = { actions.selectionAction(TextAction.Copy) }) {
                SingleLineLabel(stringResource(R.string.reader_highlight_copy))
            }
            TextButton(
                onClick = actions::deleteHighlight,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                SingleLineLabel(stringResource(R.string.reader_highlight_delete))
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Button(
            onClick = {
                // The panel closes: the keyboard goes with it rather than staying over the page.
                focus.clearFocus()
                keyboard?.hide()
                actions.saveNote()
            },
            enabled = changed,
        ) {
            SingleLineLabel(stringResource(R.string.reader_highlight_save))
        }
    }
}

@Composable
private fun DiscardDialog(actions: ReaderActions) {
    AlertDialog(
        onDismissRequest = actions::keepEditing,
        title = { Text(stringResource(R.string.reader_note_discard_title)) },
        confirmButton = {
            TextButton(onClick = actions::discardNote) { SingleLineLabel(stringResource(R.string.reader_note_discard)) }
        },
        dismissButton = {
            TextButton(onClick = actions::keepEditing) {
                SingleLineLabel(stringResource(R.string.reader_note_keep_editing))
            }
        },
    )
}
