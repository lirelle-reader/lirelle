package app.tfdn.lirelle.feature.settings.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.SettingsPageFrame
import app.tfdn.lirelle.feature.settings.R

/** The test tag of the row that opens the app's own licence. */
internal const val AboutAppLicenceRowTag = "about-app-licence-row"

/** The test tag of the row that opens the licences. */
internal const val AboutLicencesRowTag = "about-licences-row"

private val MarkSize = 72.dp
private val MarkInitialSize = 40.dp
private val RowMinHeight = 56.dp
private val Gutter = 16.dp

/**
 * About: the app's mark, its name, what it is and its copyright, its version, its licence, and the way to the
 * licences of what it uses.
 *
 * @param onOpenAppLicence opens the app's own licence.
 * @param onOpenLicences opens the licences; null where they are shown beside, and there is no row for them.
 * @param onBack leaves About.
 */
@Composable
internal fun AboutPage(
    appVersion: String,
    onOpenAppLicence: () -> Unit,
    onOpenLicences: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageFrame(title = stringResource(R.string.about_title), onBack = onBack, modifier = modifier) {
        AppHeader()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        VersionRow(appVersion)
        LinkRow(
            title = stringResource(R.string.about_app_licence),
            about = stringResource(R.string.about_app_licence_about),
            tag = AboutAppLicenceRowTag,
            onClick = onOpenAppLicence,
        )
        if (onOpenLicences != null) {
            LinkRow(
                title = stringResource(R.string.about_licences),
                about = stringResource(R.string.about_licences_about),
                tag = AboutLicencesRowTag,
                onClick = onOpenLicences,
            )
        }
    }
}

/** The app's mark over its name, the line saying what it is, and its copyright. */
@Composable
private fun AppHeader() {
    val name = stringResource(R.string.about_app_name)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppMark(initial = name.take(1))
        Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text = stringResource(R.string.about_what),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.about_copyright),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The app's mark: its initial on a rounded square in the primary colour. A drawing, so the letter keeps its
 * size whatever the text size, and a screen reader skips it: the name is under it.
 */
@Composable
private fun AppMark(initial: String) {
    val initialSize = with(LocalDensity.current) { MarkInitialSize.toSp() }
    Box(
        modifier = Modifier
            .size(MarkSize)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = TextStyle(fontSize = initialSize, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** "Version" and the version's name, which goes under it when both do not fit on one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionRow(appVersion: String) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = appVersion,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A row that leads to another page: its name over what it holds, and a chevron. */
@Composable
private fun LinkRow(title: String, about: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row says where it leads: the chevron is decoration.
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
