package app.tfdn.lirelle.feature.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The preview's two lines, for tests. */
const val HeaderPreviewTag = "reader_preview_header"
const val FooterPreviewTag = "reader_preview_footer"

private const val SamplePercent = 42
private const val SampleChapterPercent = 67
private const val SampleBattery = 80
private val SampleTime = LocalTime.of(21, 15)
private const val FaintInk = 0.25f

/**
 * What the header and the footer show: a preview in the page's colours, then the four choices.
 * A choice opens in place, one at a time, on the eight things it can be.
 */
@Composable
internal fun RunningLineSettings(state: ReaderUiState, actions: ReaderActions, modifier: Modifier = Modifier) {
    SheetFrame(
        title = stringResource(R.string.reader_settings_running_lines),
        onDone = actions::dismissSheet,
        onBack = { actions.showSheet(ReaderSheet.Settings) },
        modifier = modifier,
    ) {
        SectionLabel(stringResource(R.string.reader_running_preview))
        RunningLinesPreview(state.settings, state.colours)
        Spacer(Modifier.height(8.dp))
        RunningLineChoices(state.settings, actions::updateSettings)
    }
}

/** A small page in the colours of the reading theme, with sample values where the lines go. */
@Composable
internal fun RunningLinesPreview(settings: ReaderSettings, colours: ThemeColours) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = colours.background,
        contentColor = colours.foreground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewLine(settings.header, R.string.reader_running_preview_header, HeaderPreviewTag)
            repeat(3) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(colours.foreground.copy(alpha = FaintInk), MaterialTheme.shapes.extraSmall),
                )
            }
            PreviewLine(settings.footer, R.string.reader_running_preview_footer, FooterPreviewTag)
        }
    }
}

/** One line of the preview: the left value at the start, the right one at the end, each wrapping in its half. */
@Composable
internal fun PreviewLine(line: RunningLine, @StringRes spokenAs: Int, tag: String) {
    val left = line.left.sample()
    val right = line.right.sample()
    val both = listOfNotNull(left, right)
    val spoken = stringResource(
        spokenAs,
        if (both.isEmpty()) stringResource(R.string.reader_running_preview_empty) else both.joinToString(),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val style = MaterialTheme.typography.labelMedium
        Text(left.orEmpty(), Modifier.weight(1f), style = style)
        Text(right.orEmpty(), Modifier.weight(1f), textAlign = TextAlign.End, style = style)
    }
}

/** What this slot could show in a book, or null for nothing. */
@Composable
internal fun RunningSlot.sample(): String? = when (this) {
    RunningSlot.Nothing -> null
    RunningSlot.BookTitle -> stringResource(R.string.reader_sample_book_title)
    RunningSlot.ChapterTitle -> stringResource(R.string.reader_sample_chapter_title)
    RunningSlot.BookPercent -> stringResource(R.string.reader_sample_percent, SamplePercent)
    RunningSlot.ChapterPercent -> stringResource(R.string.reader_sample_percent, SampleChapterPercent)
    RunningSlot.PagesLeftInChapter -> stringResource(R.string.reader_sample_pages_left)
    RunningSlot.Time -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(LocalConfiguration.current.locales[0])
        .format(SampleTime)
    RunningSlot.Battery -> stringResource(R.string.reader_sample_percent, SampleBattery)
}
