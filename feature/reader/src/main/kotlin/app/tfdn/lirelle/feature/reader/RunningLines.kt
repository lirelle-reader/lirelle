package app.tfdn.lirelle.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.delay
import app.tfdn.lirelle.core.ui.R as UiR

internal const val RunningHeaderTag = "reader-running-header"
internal const val RunningFooterTag = "reader-running-footer"

private const val MillisPerMinute = 60_000L
private const val Hundred = 100

/** How much of the page's text colour the running lines keep: they are quieter than the text. */
private const val MutedAlpha = 0.7f

private val LineSidePadding = 16.dp
private val LineUprightPadding = 4.dp

/** The least room left between the two slots of a line. */
private val SlotGap = 16.dp

/** The least room left between what is in the middle of a line and either slot. */
private val CentreGap = 8.dp

/**
 * The empty band the page keeps at its bottom in paginated layout, where no text of the book is:
 * `BAND_MARGIN_PX` of `reader.js`, in CSS pixels, which are density-independent pixels in the web view.
 */
internal val PageBottomBand = 24.dp

/**
 * What the header and the footer show of the device.
 *
 * @property time as the device writes it, null to show none.
 * @property batteryPercent from 0 to 100, null when it is not known.
 * @property now the moment it is, from which the age of a newer position and the days of the marks
 *   are told; null to tell no age, and the marks' days from the clock.
 */
data class DeviceReadings(val time: String? = null, val batteryPercent: Int? = null, val now: Instant? = null)

/**
 * The header or the footer of the page: one line, a slot at each end. A slot too long for its room
 * ends with an ellipsis; a line whose two slots have nothing to show takes no room at all, unless
 * [keep] holds it.
 *
 * @param minHeight the least height of the line, whatever it shows.
 * @param centre what the middle of the line holds, if anything: it keeps its own width, and the
 *   two slots share what is left, the same on each side. It takes no part in the line's height:
 *   its bottom is the line's, and it rises above the line when it is higher.
 */
@Composable
internal fun RunningLineRow(
    line: RunningLine,
    state: ReaderUiState,
    device: DeviceReadings,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    keep: Boolean = false,
    centre: (@Composable () -> Unit)? = null,
) {
    val left = slotText(line.left, state, device)
    val right = slotText(line.right, state, device)
    val held = keep || centre != null
    if (left == null && right == null && !held) return
    Layout(
        // In this order for a screen reader too: the left slot, the middle, the right slot.
        content = {
            SlotText(left)
            centre?.invoke()
            SlotText(right)
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LineSidePadding),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val middle = if (centre != null) measurables[1].measure(Constraints(maxWidth = width)) else null
        val (leftWidth, rightWidth) = if (middle == null) {
            val wanted = listOf(measurables.first(), measurables.last())
                .map { it.maxIntrinsicWidth(constraints.maxHeight) }
            shareWidth((width - SlotGap.roundToPx()).coerceAtLeast(0), wanted[0], wanted[1])
        } else {
            val side = sideWidth(width, middle.width, CentreGap.roundToPx())
            side to side
        }
        val leftPlaceable = measurables.first().measure(Constraints(maxWidth = leftWidth))
        val rightPlaceable = measurables.last().measure(Constraints(maxWidth = rightWidth))
        val slots = maxOf(leftPlaceable.height, rightPlaceable.height) + LineUprightPadding.roundToPx() * 2
        val height = maxOf(minHeight.roundToPx(), slots)
        layout(width, height) {
            leftPlaceable.placeRelative(0, (height - leftPlaceable.height) / 2)
            middle?.placeRelative((width - middle.width) / 2, height - middle.height)
            rightPlaceable.placeRelative(width - rightPlaceable.width, (height - rightPlaceable.height) / 2)
        }
    }
}

@Composable
internal fun RunningHeader(state: ReaderUiState, device: DeviceReadings, modifier: Modifier = Modifier) =
    RunningLineRow(state.settings.header, state, device, modifier.testTag(RunningHeaderTag))

/** After a jump, "Go back" is offered: in the search's bar while it is shown, else in the footer. */
internal val ReaderUiState.goBackOffered: Boolean
    get() = goBackShown && !searchBarOnPage

/**
 * The footer: one small line, as high whether "Go back" is there or not, so that the page keeps its
 * size when it comes and goes. After a jump "Go back" sits in its middle, its bottom on the line's,
 * and rises above the line, never further than the empty band the page keeps at its bottom: in a
 * paginated book it covers no text, in a scrolled one it may cover a little while it is offered,
 * which the reader can scroll away. Only when it is higher than the line and that band together,
 * at a large text size, is the line higher. The line keeps its room while a footnote hides "Go
 * back" or the controls' bar holds it, so that the page does not move under them; a line whose
 * slots show nothing takes room only while "Go back" is offered.
 *
 * @param onGoBack what "Go back" does.
 */
@Composable
internal fun RunningFooter(
    state: ReaderUiState,
    device: DeviceReadings,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inLine = state.goBackOffered && !state.noteOpen && !state.controlsShown
    // One line of a slot, which the line holds even when its slots show nothing.
    val oneLine = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } +
        LineUprightPadding * 2
    RunningLineRow(
        line = state.settings.footer,
        state = state,
        device = device,
        modifier = modifier.testTag(RunningFooterTag),
        minHeight = maxOf(oneLine, goBackRoom() - PageBottomBand),
        keep = state.goBackOffered,
        centre = if (inLine) { { GoBackButton(onGoBack, pillAlignment = Alignment.BottomCenter) } } else null,
    )
}

@Composable
private fun SlotText(text: String?) {
    if (text == null) {
        Spacer(Modifier)
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MutedAlpha),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** What a slot shows now, null when it has nothing to show. */
@Composable
private fun slotText(slot: RunningSlot, state: ReaderUiState, device: DeviceReadings): String? {
    val location = state.location
    return when (slot) {
        RunningSlot.Nothing -> null
        RunningSlot.BookTitle -> state.title
        RunningSlot.ChapterTitle -> location?.chapter
        RunningSlot.BookPercent -> location?.let { stringResource(UiR.string.progress_percent, it.percent) }
        RunningSlot.ChapterPercent -> location?.chapterPercent?.let { stringResource(UiR.string.progress_percent, it) }
        RunningSlot.PagesLeftInChapter -> location?.pagesLeftInChapter?.let {
            pluralStringResource(R.plurals.reader_pages_left, it, it)
        }
        RunningSlot.Time -> device.time
        RunningSlot.Battery -> device.batteryPercent?.let { stringResource(UiR.string.progress_percent, it) }
    }?.takeIf(String::isNotBlank)
}

/** The time of day as the device writes it, on 24 or 12 hours following its setting. */
internal fun timeText(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

/** The time of day, written again each time the minute changes. */
@Composable
internal fun rememberTimeText(now: () -> Long = System::currentTimeMillis): String? {
    val context = LocalContext.current
    val currentNow by rememberUpdatedState(now)
    var text by remember { mutableStateOf(timeText(context, now())) }
    LaunchedEffect(context) {
        while (true) {
            text = timeText(context, currentNow())
            delay(MillisPerMinute - currentNow() % MillisPerMinute)
        }
    }
    return text
}

/** The charge of the battery from 0 to 100, followed as it changes; null when the device does not say. */
@Composable
internal fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                percent = intent.batteryPercent()
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // The system answers at once with the last value it broadcast.
        val last = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        percent = last?.batteryPercent()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else (level * Hundred / scale).coerceIn(0, Hundred)
}
