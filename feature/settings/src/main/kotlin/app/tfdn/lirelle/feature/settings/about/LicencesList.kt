package app.tfdn.lirelle.feature.settings.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.SettingsPageScaffold
import app.tfdn.lirelle.feature.settings.R

/** The width a list on one column keeps to, as the other pages do. */
private val OneColumnMaxWidth = 600.dp

private val Gutter = 16.dp

/**
 * The licences: a hint, then what is bundled in the app, then the libraries, a row for each component.
 *
 * @param licences null while the list is read: there is then no row yet.
 * @param columns the columns the rows are laid on: one on a screen of its own, two in the pane of a wide window.
 * @param onBack returns to About; null inside a pane, where About is beside.
 */
@Composable
internal fun LicencesListPage(
    licences: Licences?,
    columns: Int,
    state: LazyGridState,
    onChoose: (Component) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(title = stringResource(R.string.about_licences), onBack = onBack, modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .align(if (onBack != null) Alignment.TopCenter else Alignment.TopStart)
                .widthIn(max = if (columns == 1) OneColumnMaxWidth else Dp.Unspecified)
                .fillMaxWidth()
                .fillMaxHeight(),
            state = state,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (licences != null) {
                wholeLine { Hint() }
                group(R.string.licences_bundled, licences.bundled, onChoose)
                group(R.string.licences_libraries, licences.libraries, onChoose)
            }
        }
    }
}

/** A group of components under its title; nothing when the group is empty. */
private fun LazyGridScope.group(title: Int, components: List<Component>, onChoose: (Component) -> Unit) {
    if (components.isEmpty()) return
    wholeLine { GroupTitle(stringResource(title)) }
    items(components, contentType = { "component" }) { component ->
        LicenceRow(component = component, onClick = { onChoose(component) })
    }
}

/** An item that takes the whole width, whatever the number of columns. */
private fun LazyGridScope.wholeLine(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Hint() {
    Text(
        text = stringResource(R.string.licences_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
    )
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}
