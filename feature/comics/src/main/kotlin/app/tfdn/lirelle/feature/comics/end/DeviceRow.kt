package app.tfdn.lirelle.feature.comics.end

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.R as DownloadsR
import app.tfdn.lirelle.core.downloads.formatsName
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.comics.R

/**
 * What the device keeps of the book, as the book reader's panel says it of its own: "On this device", its
 * formats and their size, with "Delete"; once asked, that the book goes from the device, with "Undo". The texts
 * take the lines they need, and the button goes under them when a large text size leaves no room beside them
 * for their longest word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceRow(copy: DeviceCopy, enabled: Boolean, actions: DeviceCopyActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (copy.deleting) {
                Text(
                    text = stringResource(R.string.comics_end_device_deleted),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Text(text = stringResource(R.string.comics_end_device), style = MaterialTheme.typography.labelLarge)
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
            val label = if (copy.deleting) R.string.comics_end_device_undo else R.string.comics_end_device_delete
            Text(text = stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
