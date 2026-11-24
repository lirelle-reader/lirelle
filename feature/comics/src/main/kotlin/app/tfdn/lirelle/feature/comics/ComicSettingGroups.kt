package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages

/*
 * The settings that belong to no series, as the reader's sheet and the Comics page of the settings both show
 * them. They take the colours of the theme they are drawn in: the reader's dark look, or the app's theme.
 */

@Composable
internal fun FitGroup(fit: Fit, onSelect: (Fit) -> Unit) {
    SettingGroup(stringResource(R.string.comics_settings_fit)) {
        val choices = listOf(
            Fit.Page to stringResource(R.string.comics_fit_page),
            Fit.Width to stringResource(R.string.comics_fit_width),
        )
        SegmentedChoice(choices, fit, onSelect)
    }
}

@Composable
internal fun TwoPagesGroup(twoPages: TwoPages, onSelect: (TwoPages) -> Unit) {
    SettingGroup(stringResource(R.string.comics_settings_two_pages)) {
        val choices = listOf(
            TwoPages.Automatic to stringResource(R.string.comics_two_pages_automatic),
            TwoPages.Always to stringResource(R.string.comics_two_pages_always),
            TwoPages.Never to stringResource(R.string.comics_two_pages_never),
        )
        SegmentedChoice(choices, twoPages, onSelect)
        Note(stringResource(R.string.comics_two_pages_note))
    }
}

@Composable
internal fun BackgroundGroup(background: Background, onSelect: (Background) -> Unit) {
    SettingGroup(stringResource(R.string.comics_settings_background)) {
        val choices = listOf(
            Background.Black to stringResource(R.string.comics_background_black),
            Background.Grey to stringResource(R.string.comics_background_grey),
            Background.White to stringResource(R.string.comics_background_white),
        )
        SegmentedChoice(choices, background, onSelect)
    }
}

/**
 * Reading panel by panel: whether each page is shown whole before its first panel, and a word on how the panels
 * are found. The whole row is the switch, with under its name what it does.
 */
@Composable
internal fun PanelsGroup(wholePageFirst: Boolean, onChange: (Boolean) -> Unit) {
    SettingGroup(stringResource(R.string.comics_settings_panels)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = wholePageFirst, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = MinTarget),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.comics_panels_whole_first),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.comics_panels_whole_first_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = wholePageFirst, onCheckedChange = null)
        }
        Note(stringResource(R.string.comics_panels_note))
    }
}

/** A setting: its name, a heading, then its choices and what there is to say of them. */
@Composable
internal fun SettingGroup(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = name,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        content()
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
