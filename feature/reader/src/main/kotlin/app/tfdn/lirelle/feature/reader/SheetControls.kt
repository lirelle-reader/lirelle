package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

/** The sheet itself, for tests: what is above it is the page, left uncovered. */
const val ReaderSheetTag = "reader_sheet"

/** How much of the height a sheet may take: the page stays visible above it. */
internal const val MaxSheetFraction = 0.6f

private val SheetMaxWidth = 640.dp
private val SheetCorner = 20.dp
private val RowMinHeight = 56.dp
private val Gutter = 16.dp

/** The smallest side of anything that can be pressed. */
internal val MinTarget = 48.dp

/**
 * A sheet over the bottom of the page. Nothing is drawn above it: the page shows there, with each
 * change applied at once; a tap there closes the sheet. The title stays in place and [content] scrolls.
 *
 * @param onBack where the back arrow leads, for a sheet opened from another one; null for no arrow.
 */
@Composable
internal fun SheetFrame(
    title: String,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Behind the sheet, not around it: a tap on the sheet itself must not close it.
        Box(Modifier.matchParentSize().pointerInput(onDone) { detectTapGestures { onDone() } })
        Surface(
            modifier = Modifier
                .testTag(ReaderSheetTag)
                .widthIn(max = SheetMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * MaxSheetFraction),
            shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                SheetHeader(title, onDone, onBack)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = Gutter),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onDone: () -> Unit, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack == null) Gutter else 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(MinTarget)) {
                Icon(SheetIcons.Back, contentDescription = stringResource(R.string.reader_settings_back))
            }
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onDone, modifier = Modifier.heightIn(min = MinTarget)) {
            SingleLineLabel(stringResource(R.string.reader_settings_done))
        }
    }
}

/** The name of a group of settings. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** A setting that is on or off. The whole row is the switch; its texts wrap. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One choice among several. The whole row is the radio button; its text wraps. */
@Composable
internal fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A row that leads somewhere or opens something, with its current value under its name. Both
 * wrap: the row grows with them, which is why it is not announced as a button of fixed size.
 */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = { Icon(SheetIcons.Forward, contentDescription = null) },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        trailing()
    }
}
