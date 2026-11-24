package app.tfdn.lirelle.feature.settings.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.settings.R

/**
 * What stands above the app's licence, in the reader's language: the app's name, its copyright, the freedoms the
 * licence gives, the absence of warranty, and, where the notice is a translation, that the licence below is the
 * English original.
 */
@Composable
internal fun AppLicenceNotice(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        listOf(
            stringResource(R.string.app_licence_copyright),
            stringResource(R.string.app_licence_free),
            stringResource(R.string.app_licence_warranty),
            stringResource(R.string.app_licence_original),
        ).filter { it.isNotBlank() }.forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}
