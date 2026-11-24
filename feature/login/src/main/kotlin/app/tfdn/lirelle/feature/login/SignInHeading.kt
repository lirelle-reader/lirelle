package app.tfdn.lirelle.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val HeadingSpacing = 4.dp

/**
 * What a sign-in step says it is for, above its form.
 *
 * - Nobody signed in: [firstTitle], the step's own title.
 * - A profile added: that, and that the profiles stay apart.
 * - A profile signing in again: that, the profile, and its server — which is not offered to be changed, so
 *   the step shows no server line of its own.
 *
 * The profile and its server stay on one line each, ending with an ellipsis when they are long: a username and
 * an address have nowhere to break.
 *
 * @param again the profile that signs in again; null for the other purposes, and while it cannot be found.
 */
@Composable
internal fun SignInHeading(purpose: SignInPurpose, firstTitle: String, again: ProfileLabel? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(HeadingSpacing)) {
        when (purpose) {
            SignInPurpose.First -> Title(firstTitle)
            SignInPurpose.Add -> {
                Title(stringResource(R.string.add_profile_title))
                Text(
                    text = stringResource(R.string.add_profile_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SignInPurpose.Again -> {
                Title(stringResource(R.string.sign_in_again_title))
                if (again != null) {
                    OneLine(again.text(), MaterialTheme.typography.bodyLarge)
                    OneLine(
                        text = again.serverUrl,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun OneLine(text: String, style: TextStyle) {
    Text(text = text, overflow = TextOverflow.Ellipsis, maxLines = 1, style = style)
}
