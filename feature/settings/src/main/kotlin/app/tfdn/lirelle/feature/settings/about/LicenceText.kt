package app.tfdn.lirelle.feature.settings.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.SettingsPageScaffold

/** The width the text keeps to, so that its lines stay readable on a wide window. */
private val TextMaxWidth = 600.dp

/**
 * The licences of a component under its name: the name of each licence, then its text, which can be selected.
 * A licence the list has no text for shows its name alone. The app's own licence has its notice above it.
 *
 * @param inPane the text is beside About: it starts at the pane's start rather than in the middle.
 * @param onBack returns to where the text was opened from: the list, or About for the app's licence on a narrow
 *   window.
 */
@Composable
internal fun LicenceTextPage(
    component: Component,
    inPane: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(title = component.name.withBreakPlaces(), onBack = onBack, modifier = modifier) {
        SelectionContainer(
            modifier = Modifier
                .align(if (inPane) Alignment.TopStart else Alignment.TopCenter)
                .widthIn(max = TextMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                if (component.id == AppLicenceId) AppLicenceNotice(Modifier.padding(bottom = 8.dp))
                component.licences.forEach { licence ->
                    Text(
                        text = licence.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 8.dp)
                            .semantics { heading() },
                    )
                    if (licence.text != null) {
                        Text(
                            text = licence.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
