package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ContentMaxWidth = 600.dp

/**
 * The frame the settings pages made of a list are drawn in: the page's title, and the content scrolling under
 * it, no wider than what reads well. A page that lays itself out on the whole width uses
 * [SettingsPageScaffold] instead.
 *
 * A page shown on its own has a back arrow, and its content is centred on a wide window. A page shown in a
 * pane, beside the list it was chosen from, keeps its title, has no arrow, and its content starts at the
 * pane's start.
 *
 * @param onBack leaves the page; null inside a pane.
 */
@Composable
fun SettingsPageFrame(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsPageScaffold(title = title, onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier
                .align(if (onBack != null) Alignment.TopCenter else Alignment.TopStart)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}
