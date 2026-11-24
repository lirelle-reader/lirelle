package app.tfdn.lirelle.feature.reader.settings

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.ui.SettingsPageScaffold
import app.tfdn.lirelle.feature.reader.LayoutSettings
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.RunningLineChoices
import app.tfdn.lirelle.feature.reader.SettingsChange
import app.tfdn.lirelle.feature.reader.TextSettings

/** The column of the controls where it scrolls on its own, the preview staying in place, for tests. */
const val ReadingControlsTag = "reading-controls"

private val Gutter = 16.dp

/** The widest the content is on one column: what reads well, as on the other settings pages. */
private val OneColumnMaxWidth = 600.dp

/** On one column the preview stays in place where the page has at least this height, */
private val FixedPreviewMinHeight = 480.dp

/** and it takes this share of the height at most, the line under it included. */
private const val FixedPreviewMaxShare = 0.4f

/** With the preview beside them, the controls have at least this width, */
private val ControlsMinWidth = 400.dp

/** and at most this one. */
private val ControlsMaxWidth = 600.dp

/** The preview's own width beside the controls, and what is left free around it: a gutter at each side. */
private val PreviewWidth = 320.dp
private val PreviewSurround = Gutter * 2

/**
 * What one stepper of a pair needs of width for its value never to break inside a word: its gutters, its two
 * buttons and the room around the value, which do not grow with the text,
 */
private val StepperFixedWidth = 144.dp

/** and the longest word a value has, at the default text size, which does. */
private val StepperWordWidth = 72.dp

/** @param onBack null inside a pane: the page then shows its title and no arrow. */
@Composable
fun ReadingSettingsRoute(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ReadingSettingsViewModel>()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    ReadingSettingsPage(
        settings = settings,
        change = viewModel::change,
        onBack = onBack,
        modifier = modifier,
        cornerBookmark = viewModel.cornerBookmark,
    )
}

/**
 * The reading settings: every setting of the reader's sheets but the brightness level, which is set while
 * reading, and a preview of the text they give.
 *
 * Where the page has room for both, the preview stays in place beside the controls, which scroll. Elsewhere
 * the preview is on top, on a width that reads well: centred when the page is on its own, at the pane's start
 * in a pane. It stays in place there too, the controls scrolling under it, unless the page is too short for
 * that: all of it then scrolls together.
 *
 * @param settings null until they are known: only the title shows before.
 * @param onBack null inside a pane: the page then shows its title and no arrow.
 * @param cornerBookmark false for a source without annotations: no corner's bookmark among the gestures.
 */
@Composable
internal fun ReadingSettingsPage(
    settings: ReaderSettings?,
    change: SettingsChange,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    cornerBookmark: Boolean = true,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.reader_reading_settings_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (settings != null) {
            val alignment = if (onBack != null) Alignment.TopCenter else Alignment.TopStart
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= ControlsMinWidth + PreviewSurround + PreviewWidth) {
                    TwoColumns(settings, change, maxWidth, cornerBookmark, Modifier.align(alignment))
                } else if (maxHeight >= FixedPreviewMinHeight) {
                    FixedPreviewColumn(settings, change, maxHeight, cornerBookmark, Modifier.align(alignment))
                } else {
                    OneColumn(settings, change, cornerBookmark, Modifier.align(alignment))
                }
            }
        }
    }
}

/** The page on one column without the height to keep the preview in place: all of it scrolls together. */
@Composable
private fun OneColumn(
    settings: ReaderSettings,
    change: SettingsChange,
    cornerBookmark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = OneColumnMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Preview(settings, Modifier.padding(start = Gutter, end = Gutter, top = 8.dp))
        Controls(settings, change, columns = 1, cornerBookmark)
    }
}

/**
 * The phone's page: the preview staying in place at the top, and the controls scrolling under it, so that the
 * effect of a setting far down the page is seen. The preview never takes more than its share of the height: a
 * taller one, which very large text makes it, scrolls inside its card.
 *
 * @param height the height the page has.
 */
@Composable
private fun FixedPreviewColumn(
    settings: ReaderSettings,
    change: SettingsChange,
    height: Dp,
    cornerBookmark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = OneColumnMaxWidth)
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        Preview(
            settings = settings,
            modifier = Modifier
                .heightIn(max = height * FixedPreviewMaxShare)
                .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp),
            scrolls = true,
        )
        // The edge the controls go under.
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(ReadingControlsTag)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Controls(settings, change, columns = 1, cornerBookmark)
        }
    }
}

/**
 * The controls scrolling in one column, and the preview staying in place in a second one. A preview taller
 * than the page, which very large text makes it, scrolls in its own column: nothing of it is out of reach.
 *
 * @param width the width the page has.
 */
@Composable
private fun TwoColumns(
    settings: ReaderSettings,
    change: SettingsChange,
    width: Dp,
    cornerBookmark: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewColumn = PreviewWidth + PreviewSurround
    val controlsWidth = minOf(width - previewColumn, ControlsMaxWidth)
    // Two steppers on a line only where each has room for its value at the text size in use.
    val pair = (StepperFixedWidth + StepperWordWidth * LocalDensity.current.fontScale) * 2
    Row(modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(controlsWidth)
                .fillMaxHeight()
                .testTag(ReadingControlsTag)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Controls(settings, change, columns = if (controlsWidth >= pair) 2 else 1, cornerBookmark)
        }
        Column(
            modifier = Modifier
                .width(previewColumn)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 24.dp),
        ) {
            Preview(settings)
        }
    }
}

/**
 * The preview and, under it, the line that says it is an approximation.
 *
 * @param scrolls whether the card takes the height left by the line when it has not all it needs, and scrolls
 * inside itself; the height must then be bounded.
 */
@Composable
private fun Preview(settings: ReaderSettings, modifier: Modifier = Modifier, scrolls: Boolean = false) {
    Column(modifier) {
        ReadingPreview(settings, if (scrolls) Modifier.weight(1f, fill = false) else Modifier, scrolls)
        Text(
            text = stringResource(R.string.reader_preview_approximate),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The three sections of the page, which are the content of the reader's three sheets.
 *
 * @param columns how many steppers, and how many ends of the header and the footer, sit on one line.
 */
@Composable
private fun Controls(settings: ReaderSettings, change: SettingsChange, columns: Int, cornerBookmark: Boolean) {
    SectionTitle(stringResource(R.string.reader_settings_text))
    TextSettings(settings, change, columns)
    SectionTitle(stringResource(R.string.reader_settings_running_lines))
    RunningLineChoices(settings, change, columns)
    SectionTitle(stringResource(R.string.reader_settings_layout_and_gestures))
    LayoutSettings(settings, change, cornerBookmark = cornerBookmark)
}

/** The name of a section of the page, over the names of the groups it holds. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = Gutter, end = Gutter, top = 24.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
    )
}
