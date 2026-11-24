package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.formatsName
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.downloads.R as DownloadsR

internal const val EndPanelSheetTag = "reader-end-panel-sheet"
internal const val EndPanelGripTag = "reader-end-panel-grip"

private const val StarCount = 5

/** How much the page is dimmed behind the sheet. */
private const val ScrimAlpha = 0.4f

/** On a wide window the sheet stays a sheet, centred, instead of a band across the screen. */
private val SheetMaxWidth = 640.dp
private val SheetCorner = 28.dp

/** The smallest side of anything that takes a tap. */
private val TouchTarget = 48.dp
private val StarSize = 32.dp
private val ProgressSize = 24.dp
private val ProgressStroke = 3.dp

/** The space between two words of a label, as a share of the size of its text. */
private const val WordGapEm = 0.28f

/** The sides where the sheet keeps clear of the system bars and cut-outs; its content sees to the bottom. */
private val AroundSheet = WindowInsetsSides.Top + WindowInsetsSides.Horizontal

/**
 * The panel at the end of the book: a sheet at the bottom of its slot, which it fills, in the
 * colours of the reading theme. The page behind is dimmed and takes no touch while it is shown.
 * Show it while the state has a panel. A swipe down on the sheet, from its grip or anywhere on it,
 * and a tap above it dismiss it as the system's back does, which is the screen's business.
 *
 * @param title the book's.
 */
@Composable
fun EndPanelSheet(
    panel: EndPanel,
    title: String,
    colours: ThemeColours,
    actions: EndPanelActions,
    modifier: Modifier = Modifier,
) {
    ReaderChromeTheme(colours) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ScrimAlpha))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(AroundSheet)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Behind the sheet, not around it: a tap on the sheet itself must not dismiss it. A tap
            // above it stops here too: it never turns the page underneath.
            Box(Modifier.matchParentSize().pointerInput(actions) { detectTapGestures { actions.dismissEndPanel() } })
            DraggableSheet(
                onDismiss = actions::dismissEndPanel,
                modifier = Modifier
                    .testTag(EndPanelSheetTag)
                    .widthIn(max = SheetMaxWidth)
                    .fillMaxWidth(),
            ) {
                Surface(shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SheetGrip(Modifier.testTag(EndPanelGripTag).padding(top = 10.dp))
                        EndPanelContent(
                            panel = panel,
                            title = title,
                            actions = actions,
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** What the sheet holds. It scrolls when a large text size makes it taller than the screen. */
@Composable
internal fun EndPanelContent(panel: EndPanel, title: String, actions: EndPanelActions, modifier: Modifier = Modifier) {
    val enabled = !panel.sending
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_end_finished),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        // Without ratings at the source, no stars: the next volume and the rest stay (spec 2c).
        if (panel.ratings) {
            Text(text = stringResource(R.string.reader_end_rating), style = MaterialTheme.typography.bodyMedium)
            Stars(rating = panel.rating, enabled = enabled, onRate = actions::setRating)
        }
        if (panel.failed) {
            Text(
                text = stringResource(R.string.reader_end_rating_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        panel.nextVolume?.let { NextVolumeRow(it, enabled, actions::openNextVolume) }
        panel.device?.let { DeviceRow(it, enabled, actions) }
        ClosingRow(sending = panel.sending, onClose = actions::closeEndPanel)
    }
}

/** Five stars; the one chosen is the last one filled. A tap on it takes the rating away. */
@Composable
private fun Stars(rating: Int?, enabled: Boolean, onRate: (Int?) -> Unit) {
    Row {
        for (stars in 1..StarCount) {
            val chosen = rating == stars
            val filled = rating != null && stars <= rating
            IconButton(
                onClick = { onRate(if (chosen) null else stars) },
                modifier = Modifier
                    .size(TouchTarget)
                    .semantics { selected = chosen },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = pluralStringResource(R.plurals.reader_end_stars, stars, stars),
                    modifier = Modifier.size(StarSize),
                    // While the buttons wait, the stars are dimmed with them.
                    tint = if (filled && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}

/** The title keeps to one line, whatever its length: the button beside it never moves. */
@Composable
private fun NextVolumeRow(next: NextVolume, enabled: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(R.string.reader_end_next_volume), style = MaterialTheme.typography.labelLarge)
            next.title?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        FilledTonalButton(onClick = onOpen, modifier = Modifier.heightIn(min = TouchTarget), enabled = enabled) {
            Text(text = stringResource(R.string.reader_end_view), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * What the device keeps of the book, built like the next volume's row: its formats and their size, with
 * "Delete"; once asked, that the book goes from the device, with "Undo". The texts take the lines they need, and
 * the button goes under them when a large text size leaves no room beside them for their longest word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(copy: DeviceCopy, enabled: Boolean, actions: DeviceCopyActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (copy.deleting) {
                Text(
                    text = stringResource(R.string.reader_end_device_deleted),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Text(text = stringResource(R.string.reader_end_device), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = stringResource(
                        DownloadsR.string.downloads_formats_size,
                        LocalResources.current.formatsName(copy.formats, copy.textName),
                        fileSize(copy.bytes),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        // Deleting is said in the colour of what is not done lightly; undoing it is not.
        val ink = if (copy.deleting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        OutlinedButton(
            onClick = if (copy.deleting) actions::keepOnDevice else actions::deleteFromDevice,
            modifier = Modifier.heightIn(min = TouchTarget),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
        ) {
            val label = if (copy.deleting) R.string.reader_end_device_undo else R.string.reader_end_device_delete
            Text(text = stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "Close the book", which also sends the rating, and what shows that it is on its way. The label
 * is never cut short: each word is a text of one line, and the words that do not fit beside the
 * others go on further lines; a screen reader is given the label whole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClosingRow(sending: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sending) {
            val saving = stringResource(R.string.reader_end_sending)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(ProgressSize)
                    .semantics { contentDescription = saving },
                strokeWidth = ProgressStroke,
            )
        }
        val label = stringResource(R.string.reader_end_close)
        Button(
            onClick = onClose,
            modifier = Modifier
                // The progress beside it keeps its place: the button takes what is left, at most.
                .weight(1f, fill = false)
                .heightIn(min = TouchTarget)
                .semantics { contentDescription = label },
            enabled = !sending,
        ) {
            val gap = with(LocalDensity.current) { (LocalTextStyle.current.fontSize * WordGapEm).toDp() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                label.split(' ').forEach { word -> SingleLineLabel(word) }
            }
        }
    }
}
