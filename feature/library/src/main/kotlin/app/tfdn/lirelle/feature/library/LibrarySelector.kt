package app.tfdn.lirelle.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.ServerLibrary

internal const val LibrarySelectorTag = "library-selector"
internal const val LibraryCheckTag = "library-choice-check"
private const val AllLibrariesTag = "library-choice-all"

/** The smallest height of what is tapped. */
private val TouchHeight = 48.dp

private val MarkSize = 24.dp

/**
 * The Library's title. With fewer than two libraries it is the plain title of any top-level screen; from two, it
 * is a button that names the library shown and opens the menu to show another, or all of them when [offersAll].
 *
 * The title's line is at the same place and as high either way: the Library does not move when its libraries
 * arrive.
 *
 * @param roomBelow how far the button may reach below the title's line, as for the action of the title's row.
 */
@Composable
internal fun LibraryTitle(
    libraries: LibraryChoiceState,
    offersAll: Boolean,
    onChoose: (ServerLibrary?) -> Unit,
    roomBelow: Dp,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.library_title)
    if (libraries.libraries.size < 2) {
        Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        LibraryTitleButton(
            label = libraries.chosen?.name ?: title,
            description = libraries.chosen
                ?.let { stringResource(R.string.library_selector_description, it.name) }
                ?: stringResource(R.string.library_selector_description_all),
            roomBelow = roomBelow,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.selectableGroup(),
        ) {
            val choose = { library: ServerLibrary? ->
                open = false
                onChoose(library)
            }
            if (offersAll) {
                LibraryLine(stringResource(R.string.library_all), libraries.chosen == null, AllLibrariesTag) {
                    choose(null)
                }
                HorizontalDivider()
            }
            libraries.libraries.forEach { library ->
                // By its id: the list may have been read again since the choice, with another name for it.
                LibraryLine(library.name, library.id == libraries.chosen?.id, "library-choice-${library.id}") {
                    choose(library)
                }
            }
        }
    }
}

/**
 * The title as a button: its name on one line, cut with an ellipsis when the row is too narrow, and an arrow.
 *
 * It takes the room of the title's line only, and what is tapped reaches past the line to [TouchHeight]: above
 * and below alike, or less below and more above when there is less than that of [roomBelow].
 */
@Composable
private fun LibraryTitleButton(label: String, description: String, roomBelow: Dp, onClick: () -> Unit) {
    val belowAtMost = with(LocalDensity.current) { if (roomBelow == Dp.Unspecified) null else roomBelow.roundToPx() }
    // Of what the button has more than its line, the part above the line.
    val above = { extra: Int -> extra - minOf(extra / 2, belowAtMost ?: extra) }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val line = measurable.minIntrinsicHeight(constraints.maxWidth)
                val button = measurable.measure(
                    constraints.copy(minHeight = maxOf(constraints.minHeight, TouchHeight.roundToPx())),
                )
                val height = minOf(line, button.height)
                layout(button.width, height) { button.place(0, -above(button.height - height)) }
            }
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                heading()
                contentDescription = description
            }
            .testTag(LibrarySelectorTag),
        // The line stays where the plain title's is.
        contentAlignment = { size, space, _ -> IntOffset(0, above(space.height - size.height)) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProvideTextStyle(MaterialTheme.typography.headlineMedium) {
                SingleLineLabel(label, Modifier.weight(1f, fill = false))
            }
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
    }
}

/** A line of the menu, one option among those of the menu: the one shown has the check mark and is selected. */
@Composable
private fun LibraryLine(name: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        // A long name takes the lines it needs, clear of the lines around it.
        text = { Text(name, Modifier.padding(vertical = 8.dp)) },
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .semantics {
                role = Role.RadioButton
                selected = chosen
            },
        leadingIcon = {
            if (chosen) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.testTag(LibraryCheckTag))
            } else {
                // The names are in line, checked or not.
                Spacer(Modifier.size(MarkSize))
            }
        },
    )
}
