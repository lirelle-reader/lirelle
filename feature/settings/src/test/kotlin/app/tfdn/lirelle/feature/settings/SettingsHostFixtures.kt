package app.tfdn.lirelle.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The tag of the stub standing for a page. */
fun pageTag(page: SettingsPage): String = "page-${page.name}"

/** The tag of what a stub shows to go back; it is absent where the page got no way back. */
const val PageBackTag = "page-back"

/** Stands for a page of the settings: it shows which page it is, and a way back when it was given one. */
@Composable
fun StubPage(page: SettingsPage, onBack: (() -> Unit)?) {
    Column(Modifier.testTag(pageTag(page))) {
        Text(page.name)
        if (onBack != null) Text("Back", Modifier.testTag(PageBackTag).clickable(onClick = onBack))
    }
}
