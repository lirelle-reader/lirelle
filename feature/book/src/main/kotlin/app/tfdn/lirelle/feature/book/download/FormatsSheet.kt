package app.tfdn.lirelle.feature.book.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.toggled
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.book.R
import app.tfdn.lirelle.feature.book.WideAction

const val FormatsSheetTag = "formats-sheet"
const val FormatsApplyTag = "formats-apply"
const val FormatsCancelTag = "formats-cancel"

private val ChoiceSaver = listSaver<Set<DownloadFormat>, String>(
    save = { chosen -> chosen.map { it.name } },
    restore = { names -> names.mapTo(mutableSetOf()) { DownloadFormat.valueOf(it) } },
)

/**
 * The formats sheet of the book page: what to keep of the book on the device.
 *
 * @param title the book's.
 * @param onApply does what the sheet was confirmed with; the caller closes it.
 */
@Composable
fun FormatsSheet(title: String, sheet: FormatsSheetState, onDismiss: () -> Unit, onApply: (SheetPlan) -> Unit) {
    GripSheet(onDismissRequest = onDismiss) {
        FormatsSheetContent(title, sheet, onCancel = onDismiss, onApply = onApply)
    }
}

/**
 * What the sheet holds: a row to check for each format, the space left, and a button that says what it will
 * do. When that deletes something, the button first leads to a confirmation, which says what is kept. An
 * account that may not download only sees what the device keeps, to remove it.
 */
@Composable
fun FormatsSheetContent(
    title: String,
    sheet: FormatsSheetState,
    onCancel: () -> Unit,
    onApply: (SheetPlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable(stateSaver = ChoiceSaver) { mutableStateOf(sheet.situation.kept()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val plan = sheet.situation.plan(chosen)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(FormatsSheetTag)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (confirming) {
            val text = sheet.situation.textName
            Confirmation(plan, text, onBack = { confirming = false }, onConfirm = { onApply(plan) })
        } else {
            Choice(title, sheet, chosen, onToggle = { chosen = chosen.toggled(it) })
            WideAction(plan.label(removalOnly = !sheet.situation.canDownload), FormatsApplyTag, !plan.isEmpty, true) {
                if (plan.toDelete.isEmpty()) onApply(plan) else confirming = true
            }
            CancelAction(onCancel)
        }
    }
}

@Composable
private fun Choice(
    title: String,
    sheet: FormatsSheetState,
    chosen: Set<DownloadFormat>,
    onToggle: (DownloadFormat) -> Unit,
) {
    val allowed = sheet.situation.canDownload
    SheetTitle(if (allowed) R.string.download_sheet_title else R.string.download_delete_title)
    Note(if (allowed) stringResource(R.string.download_sheet_subtitle, title) else title)
    sheet.situation.sheetRows(chosen).forEach { row -> FormatRow(row, onToggle = { onToggle(row.format) }) }
    if (allowed) {
        Note(stringResource(R.string.download_sheet_free, fileSize(sheet.freeBytes)))
        if (sheet.waitsForWifi) Note(stringResource(R.string.download_sheet_metered))
    } else {
        Note(stringResource(R.string.download_line_not_allowed))
    }
}

/** Before anything is deleted: what goes, the text named as [text] says, and what stays. */
@Composable
private fun Confirmation(plan: SheetPlan, text: TextName, onBack: () -> Unit, onConfirm: () -> Unit) {
    SheetTitle(R.string.download_delete_title)
    Text(
        text = DownloadFormat.entries.filter { it in plan.toDelete }.map { it.label(text) }.joinToString(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Note(stringResource(R.string.download_delete_note))
    val label = stringResource(R.string.download_sheet_delete_size, fileSize(plan.deleteBytes))
    WideAction(label, FormatsApplyTag, enabled = true, filled = true, onClick = onConfirm)
    CancelAction(onBack)
}

@Composable
private fun SheetTitle(title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CancelAction(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FormatsCancelTag),
    ) { SingleLineLabel(stringResource(R.string.download_action_cancel)) }
}

/** What the button will do: download, delete, or both; with the size when it is known. */
@Composable
private fun SheetPlan.label(removalOnly: Boolean): String = when {
    toDownload.isNotEmpty() && toDelete.isNotEmpty() -> stringResource(R.string.download_sheet_apply)
    toDelete.isNotEmpty() -> stringResource(R.string.download_sheet_delete_size, fileSize(deleteBytes))
    toDownload.isNotEmpty() && downloadBytes != null ->
        stringResource(R.string.download_sheet_download_size, fileSize(downloadBytes))
    removalOnly -> stringResource(R.string.download_sheet_delete)
    else -> stringResource(R.string.download_sheet_download)
}
