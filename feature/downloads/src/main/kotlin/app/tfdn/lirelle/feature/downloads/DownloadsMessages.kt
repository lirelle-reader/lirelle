package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

const val DownloadsSnackbarsTag = "downloads-snackbars"

/** The room a snackbar takes around its message and its action: its margins, its paddings and the action's. */
private val SnackbarChrome = 96.dp

/**
 * Shows [snackbar] until it goes, by its "Undo" or on its own, and tells which. It stays less long than a
 * deletion can be undone.
 */
@Composable
internal fun DownloadsMessages(
    snackbar: DownloadsSnackbar?,
    host: SnackbarHostState,
    onDone: (DownloadsSnackbar, Boolean) -> Unit,
) {
    val done by rememberUpdatedState(onDone)
    val message = when (snackbar?.kind) {
        DownloadsSnackbar.Kind.MobileAllowed -> stringResource(R.string.downloads_mobile_allowed)
        DownloadsSnackbar.Kind.Deleted -> stringResource(R.string.downloads_deleted)
        DownloadsSnackbar.Kind.InUse -> stringResource(R.string.downloads_in_use)
        null -> ""
    }
    val undo = stringResource(R.string.downloads_undo)
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val action = undo.takeIf { snackbar.kind != DownloadsSnackbar.Kind.InUse }
        val result = host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short)
        done(snackbar, result == SnackbarResult.ActionPerformed)
    }
}

/**
 * A message with its action beside it when both fit on one line, under it otherwise, so that the message keeps
 * its words whole.
 */
@Composable
internal fun MessageSnackbar(data: SnackbarData) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val message = measurer.measure(data.visuals.message, MaterialTheme.typography.bodyMedium).size.width
        val action = data.visuals.actionLabel
            ?.let { measurer.measure(it, MaterialTheme.typography.labelLarge).size.width } ?: 0
        val room = constraints.maxWidth - with(LocalDensity.current) { SnackbarChrome.roundToPx() }
        Snackbar(data, actionOnNewLine = message + action > room)
    }
}
