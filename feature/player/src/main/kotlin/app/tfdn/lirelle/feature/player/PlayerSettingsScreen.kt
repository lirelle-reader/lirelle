package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

/** The intervals offered for the skip buttons, in seconds. */
private val SkipChoices = listOf(5, 10, 15, 30, 45, 60)

private val ContentMaxWidth = 600.dp
private val Gutter = 16.dp
private val RowMinHeight = 56.dp
private val ChipMinHeight = 48.dp
private val ChipCheckSize = 18.dp

/** The test tag of the chip of [seconds] in the skip [group] ("back" or "forward"). */
internal fun skipChoiceTag(group: String, seconds: Int) = "player-settings-$group-$seconds"

/**
 * The player settings with a view model of their own, for where they are shown: a screen opened from the
 * player, or a page of the app's settings.
 *
 * @param onBack leaves the settings; null inside a pane, where they show their title and no arrow.
 */
@Composable
fun PlayerSettingsRoute(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    PlayerSettingsScreen(viewModel = hiltViewModel<PlayerSettingsViewModel>(), onBack = onBack, modifier = modifier)
}

/**
 * The player settings (spec 5.6): the skip intervals, rewinding on resume, the sleep timer's fade and
 * shaking to extend it. Each change is stored at once.
 *
 * @param onBack leaves the settings; null inside a pane, where they show their title and no arrow.
 */
@Composable
fun PlayerSettingsScreen(
    viewModel: PlayerSettingsViewModel,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    PlayerSettingsContent(settings, viewModel, onBack, modifier)
}

/** The settings once read; nothing under the top bar before, so that no default shows for a moment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsContent(
    settings: PlayerSettings?,
    changes: PlayerSettingsChanges,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.player_settings_title),
                        modifier = Modifier.semantics { heading() },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.player_settings_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // In a pane the settings start at the pane's start, as the other pages do; alone, in the middle.
            contentAlignment = if (onBack == null) Alignment.TopStart else Alignment.TopCenter,
        ) {
            if (settings != null) SettingsList(settings, changes)
        }
    }
}

@Composable
private fun SettingsList(settings: PlayerSettings, changes: PlayerSettingsChanges) {
    Column(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SectionLabel(stringResource(R.string.player_settings_skip_back))
        SkipChoices("back", settings.backSeconds, changes::setBackSeconds)
        SectionLabel(stringResource(R.string.player_settings_skip_forward))
        SkipChoices("forward", settings.forwardSeconds, changes::setForwardSeconds)
        SectionLabel(stringResource(R.string.player_settings_playback))
        SwitchRow(
            label = stringResource(R.string.player_settings_rewind),
            hint = stringResource(R.string.player_settings_rewind_hint),
            checked = settings.rewindOnResume,
            onChange = changes::setRewindOnResume,
        )
        SectionLabel(stringResource(R.string.player_sleep_title))
        SwitchRow(
            label = stringResource(R.string.player_settings_fade),
            hint = stringResource(R.string.player_settings_fade_hint),
            checked = settings.sleepFade,
            onChange = changes::setSleepFade,
        )
        SwitchRow(
            label = stringResource(R.string.player_settings_shake),
            hint = stringResource(R.string.player_settings_shake_hint),
            checked = settings.shakeToExtend,
            onChange = changes::setShakeToExtend,
        )
    }
}

/** The name of a group of settings, as in the reader's settings. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** One interval among [SkipChoices]: chips that go under one another when the width runs out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipChoices(group: String, chosen: Int, onChoose: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(horizontal = Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkipChoices.forEach { seconds ->
            SkipChip(seconds, selected = seconds == chosen, Modifier.testTag(skipChoiceTag(group, seconds))) {
                onChoose(seconds)
            }
        }
    }
}

/** "15 s" on screen, "15 seconds" to a screen reader; the one chosen is filled and checked. */
@Composable
private fun SkipChip(seconds: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val spoken = pluralStringResource(R.plurals.player_settings_seconds, seconds, seconds)
    val colours = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onSelect,
        modifier = modifier
            .heightIn(min = ChipMinHeight)
            .semantics {
                role = Role.RadioButton
                contentDescription = spoken
            },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colours.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colours.onSecondaryContainer else colours.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colours.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ChipCheckSize))
            SingleLineLabel(stringResource(R.string.player_settings_seconds_short, seconds))
        }
    }
}

/** A setting that is on or off. The whole row is the switch; its texts wrap. */
@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
