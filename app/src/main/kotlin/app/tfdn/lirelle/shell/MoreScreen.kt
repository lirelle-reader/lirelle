package app.tfdn.lirelle.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.R

private val ContentMaxWidth = 600.dp

/**
 * More: the profiles, the reading history, the settings and About, one row each. Without [onOpenHistory] (a source
 * that keeps no history, spec 2c) there is no History row.
 */
@Composable
fun MoreScreen(
    onOpenProfiles: () -> Unit,
    onOpenHistory: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.destination_more),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .semantics { heading() },
            )
            val icons = Icons.Outlined
            MoreRow(icons.AccountCircle, R.string.more_account, R.string.more_account_description, onOpenProfiles)
            if (onOpenHistory != null) {
                MoreRow(icons.History, R.string.more_history, R.string.more_history_description, onOpenHistory)
            }
            MoreRow(icons.Tune, R.string.more_settings, R.string.more_settings_description, onOpenSettings)
            MoreRow(icons.Info, R.string.more_about, R.string.more_about_description, onOpenAbout)
        }
    }
}

/**
 * A row that leads elsewhere: its name over what it holds. The icon keeps the colour a list item gives it, the
 * same for all; the name says what the row is, so the icon is decoration.
 */
@Composable
private fun MoreRow(icon: ImageVector, @StringRes name: Int, @StringRes about: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(name)) },
        supportingContent = { Text(stringResource(about)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
