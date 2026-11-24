package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.MediaProfile

private val ButtonShape = RoundedCornerShape(12.dp)
private val ButtonMinHeight = 48.dp
private val ButtonPadding = 8.dp
private val ButtonGap = 8.dp
private val IconGap = 6.dp
private val IconSize = 18.dp
private const val SecondsPerMinute = 60L
private const val MinutesPerHour = 60L

/** The test tag of the button of a [medium] under the card of [bookId]. */
fun mediumButtonTag(medium: BookMedium, bookId: BookId): String = "continue-${medium.name.lowercase()}-$bookId"

/**
 * One button per medium the book has, sharing the width of the card while their values fit side by
 * side; one above the other, each as wide as the card, when they do not (large text).
 */
@Composable
internal fun MediumButtons(book: BookSummary, onRead: () -> Unit, onListen: () -> Unit, lastMedium: BookMedium?) {
    SideBySideOrStacked(ButtonGap) {
        if (book.media != MediaProfile.AudioOnly) {
            val percent = book.progressPercent ?: 0
            MediumButton(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                value = stringResource(R.string.progress_percent, percent),
                spoken = stringResource(R.string.continue_read_label, book.title),
                state = stringResource(R.string.book_progress_description, percent),
                emphasised = lastMedium == BookMedium.Read,
                onClick = onRead,
                modifier = Modifier.testTag(mediumButtonTag(BookMedium.Read, book.id)),
            )
        }
        // An audio file to play, or the narration of a read-along EPUB, which plays with its text in the reader.
        if (book.hasAudioFiles || book.media == MediaProfile.ReadAlong) {
            val seconds = book.timeLeftSeconds
            MediumButton(
                icon = Icons.Outlined.Headphones,
                value = seconds?.let { timeLeftText(it) },
                spoken = stringResource(R.string.continue_listen_label, book.title),
                state = seconds?.let { stringResource(R.string.continue_time_left_description, spokenTimeLeft(it)) },
                emphasised = lastMedium == BookMedium.Listen,
                onClick = onListen,
                modifier = Modifier.testTag(mediumButtonTag(BookMedium.Listen, book.id)),
            )
        }
    }
}

/**
 * At least 48 dp high, an icon and a short value on one line. A screen reader says what it does
 * ([spoken]) and how far the user is ([state]); the icon and the value are not said.
 */
@Composable
private fun MediumButton(
    icon: ImageVector,
    value: String?,
    spoken: String,
    state: String?,
    emphasised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (emphasised) colors.secondaryContainer else colors.surfaceContainerHighest
    val content = if (emphasised) colors.onSecondaryContainer else colors.onSurface
    Row(
        modifier = modifier
            .heightIn(min = ButtonMinHeight)
            .clip(ButtonShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            // After the click: the click stays, the icon and the value give way to what is said.
            .clearAndSetSemantics {
                contentDescription = spoken
                state?.let { stateDescription = it }
                role = Role.Button
            }
            .padding(horizontal = ButtonPadding),
        horizontalArrangement = Arrangement.spacedBy(IconGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(IconSize))
        // The value has what the icon leaves; it is short enough not to need more.
        if (value != null) SingleLineLabel(value, color = content)
    }
}

/**
 * Its children side by side at equal widths when each fits whole in its share, one above the other at
 * the whole width when one does not.
 */
@Composable
private fun SideBySideOrStacked(gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val gaps = gapPx * (measurables.size - 1).coerceAtLeast(0)
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural.sum() + gaps
        val share = if (measurables.isEmpty()) width else (width - gaps) / measurables.size
        val sideBySide = natural.all { it <= share }
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(if (sideBySide) share else width)) }
        val height = if (sideBySide) {
            placeables.maxOfOrNull { it.height } ?: 0
        } else {
            placeables.sumOf { it.height } + gaps
        }
        layout(width, height) {
            var x = 0
            var y = 0
            placeables.forEach {
                it.placeRelative(x, y)
                if (sideBySide) x += it.width + gapPx else y += it.height + gapPx
            }
        }
    }
}

/** "9 hours 12 minutes", "40 minutes": the time left in words, for a screen reader. */
@Composable
internal fun spokenTimeLeft(seconds: Long): String {
    val minutes = (seconds / SecondsPerMinute).toInt()
    val hours = minutes / MinutesPerHour.toInt()
    val rest = minutes % MinutesPerHour.toInt()
    val hoursText = pluralStringResource(R.plurals.continue_hours_spoken, hours, hours)
    val minutesText = pluralStringResource(R.plurals.continue_minutes_spoken, rest, rest)
    return when {
        hours == 0 -> minutesText
        rest == 0 -> hoursText
        else -> stringResource(R.string.continue_hours_and_minutes, hoursText, minutesText)
    }
}

/** "9 h 12", "40 min": the time left in short. */
@Composable
internal fun timeLeftText(seconds: Long): String {
    val minutes = seconds / SecondsPerMinute
    val hours = minutes / MinutesPerHour
    return if (hours > 0) {
        stringResource(R.string.continue_time_left_hours, hours, minutes % MinutesPerHour)
    } else {
        stringResource(R.string.continue_time_left_minutes, minutes)
    }
}
