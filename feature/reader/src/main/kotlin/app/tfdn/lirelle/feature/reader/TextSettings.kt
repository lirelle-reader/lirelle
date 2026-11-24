package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import java.text.NumberFormat

/** Below this width, at the default text size, a stepper's name goes above its buttons. */
private val SideBySideMinWidth = 360.dp

/**
 * How the text is drawn: the theme, the font, the size, the line spacing, the margins, the alignment and the
 * hyphenation. The reader's sheet and the Reading page of the settings both show it.
 *
 * @param columns how many steppers sit on one line: one in a sheet, two where there is room.
 */
@Composable
internal fun TextSettings(
    settings: ReaderSettings,
    change: SettingsChange,
    columns: Int = 1,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionLabel(stringResource(R.string.reader_settings_theme))
        ThemeSwatches(settings.theme) { theme -> change { it.copy(theme = theme) } }
        SectionLabel(stringResource(R.string.reader_settings_font))
        FontChoices(settings.font) { font -> change { it.copy(font = font) } }
        Steppers(settings, change, columns)
        SwitchRow(
            label = stringResource(R.string.reader_settings_hyphenation),
            checked = settings.hyphenate,
            onChange = { on -> change { it.copy(hyphenate = on) } },
        )
    }
}

/** A stepper to draw: its texts and what its two buttons do. */
private class StepperSpec(val labels: StepperLabels, val onDecrease: (() -> Unit)?, val onIncrease: (() -> Unit)?)

/**
 * Text size, line spacing, margins and alignment. Each press moves one step from where the
 * setting is when the press is handled, not from what was on screen: fast presses all count.
 */
@Composable
private fun Steppers(settings: ReaderSettings, change: SettingsChange, columns: Int) {
    val steppers = listOf(sizeStepper(settings, change), lineSpacingStepper(settings, change)) +
        marginsAndAlignmentSteppers(settings, change)
    BoxWithConstraints {
        val stacked = maxWidth / columns / LocalDensity.current.fontScale < SideBySideMinWidth
        Column(Modifier.padding(top = 12.dp)) {
            steppers.chunked(columns).forEach { line ->
                if (line.size == 1) {
                    line.single().let { Stepper(it.labels, stacked, it.onDecrease, it.onIncrease) }
                } else {
                    Row {
                        line.forEach {
                            Box(Modifier.weight(1f)) { Stepper(it.labels, stacked, it.onDecrease, it.onIncrease) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sizeStepper(settings: ReaderSettings, change: SettingsChange): StepperSpec {
    val sizeStep = settings.fontSizeStep.coerceIn(FontSizesPx.indices)
    val numbers = NumberFormat.getInstance(LocalConfiguration.current.locales[0])
    return StepperSpec(
        labels = StepperLabels(
            name = stringResource(R.string.reader_settings_size),
            value = numbers.format(FontSizesPx[sizeStep]),
            decrease = stringResource(R.string.reader_settings_size_smaller),
            increase = stringResource(R.string.reader_settings_size_larger),
        ),
        onDecrease = change.step(sizeStep > 0) { it.copy(fontSizeStep = it.fontSizeStep - 1) },
        onIncrease = change.step(sizeStep < FontSizesPx.lastIndex) { it.copy(fontSizeStep = it.fontSizeStep + 1) },
    )
}

@Composable
private fun lineSpacingStepper(settings: ReaderSettings, change: SettingsChange): StepperSpec {
    val lineStep = settings.lineHeightStep.coerceIn(LineHeights.indices)
    val numbers = NumberFormat.getInstance(LocalConfiguration.current.locales[0])
    return StepperSpec(
        labels = StepperLabels(
            name = stringResource(R.string.reader_settings_line_spacing),
            value = numbers.format(LineHeights[lineStep]),
            decrease = stringResource(R.string.reader_settings_line_spacing_tighter),
            increase = stringResource(R.string.reader_settings_line_spacing_looser),
        ),
        onDecrease = change.step(lineStep > 0) { it.copy(lineHeightStep = it.lineHeightStep - 1) },
        onIncrease = change.step(lineStep < LineHeights.lastIndex) {
            it.copy(lineHeightStep = it.lineHeightStep + 1)
        },
    )
}

@Composable
private fun marginsAndAlignmentSteppers(settings: ReaderSettings, change: SettingsChange): List<StepperSpec> = listOf(
    StepperSpec(
        labels = StepperLabels(
            name = stringResource(R.string.reader_settings_margins),
            value = stringResource(settings.margins.label),
            decrease = stringResource(R.string.reader_settings_margins_narrower),
            increase = stringResource(R.string.reader_settings_margins_wider),
        ),
        onDecrease = change.step(!settings.margins.isFirst) { it.copy(margins = it.margins.moved(-1)) },
        onIncrease = change.step(!settings.margins.isLast) { it.copy(margins = it.margins.moved(1)) },
    ),
    StepperSpec(
        labels = StepperLabels(
            name = stringResource(R.string.reader_settings_alignment),
            value = stringResource(settings.align.label),
            decrease = stringResource(R.string.reader_settings_alignment_previous),
            increase = stringResource(R.string.reader_settings_alignment_next),
        ),
        onDecrease = change.step(!settings.align.isFirst) { it.copy(align = it.align.moved(-1)) },
        onIncrease = change.step(!settings.align.isLast) { it.copy(align = it.align.moved(1)) },
    ),
)

/** What a stepper's button does, or null where it is [possible] no more. */
private fun SettingsChange.step(possible: Boolean, transform: (ReaderSettings) -> ReaderSettings): (() -> Unit)? =
    if (possible) ({ this(transform) }) else null

private val Enum<*>.isFirst: Boolean get() = ordinal == 0

private inline val <reified E : Enum<E>> E.isLast: Boolean get() = ordinal == enumValues<E>().lastIndex

/** The value [by] places further in its list, or the end of the list. */
private inline fun <reified E : Enum<E>> E.moved(by: Int): E =
    enumValues<E>().let { it[(ordinal + by).coerceIn(it.indices)] }
