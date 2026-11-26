package app.tfdn.lirelle.feature.login

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.session.ServerKind

/**
 * The sentence that says where an account makes a key on a server of this kind, in the words of that server's own
 * interface; null for a kind that has none yet.
 */
@get:StringRes
private val ServerKind.whereToMakeKey: Int?
    get() = when (this) {
        ServerKind.Kavita -> R.string.sign_in_key_where_kavita
        ServerKind.BookOrbit, ServerKind.Komga -> null
    }

/**
 * A key is random text: no correction or suggestion that would change what was typed or pasted, no capital letter
 * at the start. Not the keyboard of a password: the field would then be one to autofill, and a password manager
 * would offer the account's password for it.
 */
internal val KeyKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
    imeAction = ImeAction.Done,
)

/**
 * The sign-in with a key the user pasted, last on the sign-in screen, in its column: a line with "or" in it when
 * something stands above ([underOthers]) — the password, a provider —, the key's field with, under it, where to
 * make a key on a server of the state's kind, and the key's button.
 *
 * The key is shown as typed, so that what was pasted can be checked, and asked of [KeyKeyboard].
 */
@Composable
internal fun KeySignInBlock(
    state: SignInUiState,
    underOthers: Boolean,
    onKeyChange: (String) -> Unit,
    onKeySignIn: () -> Unit,
) {
    if (underOthers) OrLine()
    val where = state.kind?.whereToMakeKey
    OutlinedTextField(
        value = state.key,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.sign_in_key)) },
        supportingText = where?.let { sentence -> { Text(stringResource(sentence)) } },
        singleLine = true,
        keyboardOptions = KeyKeyboard,
        keyboardActions = KeyboardActions(onDone = { onKeySignIn() }),
    )
    OutlinedButton(onClick = onKeySignIn, enabled = state.canSubmitKey, modifier = Modifier.fillMaxWidth()) {
        // Longer than its neighbours: on a second line rather than cut short, at a large text size.
        WrappingLabel(stringResource(R.string.sign_in_with_key))
    }
}

/** "or", in the middle of a thin line across the column. */
@Composable
private fun OrLine() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.sign_in_or),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
