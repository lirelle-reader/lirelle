package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The share of a row's width its minus, value and plus take when they sit beside the name. */
private const val ControlShare = 0.56f

/**
 * What a stepper says.
 *
 * @property name the setting, [value] where it stands.
 * @property decrease what the minus button does, spoken; [increase] the same for plus.
 */
internal data class StepperLabels(val name: String, val value: String, val decrease: String, val increase: String)

/**
 * A setting moved along a list of values with minus and plus. The buttons do not move when the
 * value changes, so one can be pressed several times in a row.
 *
 * @param stacked the name goes above the buttons: there is no room for it beside them.
 * @param onDecrease null at the low end of the list: minus is disabled. [onIncrease] likewise.
 */
@Composable
internal fun Stepper(labels: StepperLabels, stacked: Boolean, onDecrease: (() -> Unit)?, onIncrease: (() -> Unit)?) {
    val name = @Composable { modifier: Modifier ->
        Text(labels.name, modifier = modifier, style = MaterialTheme.typography.bodyLarge)
    }
    if (stacked) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            name(Modifier.padding(top = 4.dp))
            StepperButtons(labels, onDecrease, onIncrease, Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            name(Modifier.weight(1f - ControlShare).padding(end = 8.dp))
            StepperButtons(labels, onDecrease, onIncrease, Modifier.weight(ControlShare))
        }
    }
}

@Composable
private fun StepperButtons(
    labels: StepperLabels,
    onDecrease: (() -> Unit)?,
    onIncrease: (() -> Unit)?,
    modifier: Modifier,
) {
    val spoken = stringResource(R.string.reader_settings_value, labels.name, labels.value)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(onClick = onDecrease ?: {}, Modifier.size(MinTarget), enabled = onDecrease != null) {
            Icon(SheetIcons.Minus, contentDescription = labels.decrease)
        }
        Text(
            text = labels.value,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                // Spoken with the setting's name, and again each time it changes.
                .semantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedIconButton(onClick = onIncrease ?: {}, Modifier.size(MinTarget), enabled = onIncrease != null) {
            Icon(SheetIcons.Plus, contentDescription = labels.increase)
        }
    }
}
