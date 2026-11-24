package app.tfdn.lirelle.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

/** Width cap that keeps the form readable on a tablet. */
internal val FormMaxWidth = 480.dp

/**
 * The server's address is typed as an address: the address keyboard, no correction or suggestion that would
 * change what was typed, no capital letter at the start.
 */
internal val AddressKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Go,
)

/**
 * @param purpose what the sign-in is for: its heading. Never [SignInPurpose.Again], whose server is not asked.
 */
@Composable
fun ServerRoute(
    onConnected: () -> Unit,
    purpose: SignInPurpose = SignInPurpose.First,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.connected.collect { onConnected() }
    }
    ServerScreen(
        state = state,
        onAddressChange = viewModel::onAddressChange,
        onContinue = viewModel::onContinue,
        onCleartextConfirmed = viewModel::onCleartextConfirmed,
        onCleartextDismissed = viewModel::onCleartextDismissed,
        purpose = purpose,
    )
}

@Composable
fun ServerScreen(
    state: ServerUiState,
    onAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCleartextConfirmed: () -> Unit,
    onCleartextDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    purpose: SignInPurpose = SignInPurpose.First,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = FormMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SignInHeading(purpose, firstTitle = stringResource(R.string.server_title))
            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_address_label)) },
                supportingText = { AddressSupportingText(state.error) },
                isError = state.error != null,
                singleLine = true,
                keyboardOptions = AddressKeyboard,
                keyboardActions = KeyboardActions(onGo = { onContinue() }),
            )
            Button(
                onClick = onContinue,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SingleLineLabel(stringResource(R.string.server_continue))
            }
            if (state.isChecking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    state.cleartextAddress?.let { address ->
        AlertDialog(
            onDismissRequest = onCleartextDismissed,
            title = { Text(stringResource(R.string.cleartext_dialog_title)) },
            text = {
                // Scrolls: with large text the message is taller than the room the dialog leaves it.
                Text(
                    text = stringResource(R.string.cleartext_dialog_message, address),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = onCleartextConfirmed) {
                    SingleLineLabel(stringResource(R.string.cleartext_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCleartextDismissed) {
                    SingleLineLabel(stringResource(R.string.cleartext_dialog_cancel))
                }
            },
        )
    }
}

/** The hint, or the error in its place. Only the error is announced when it appears. */
@Composable
private fun AddressSupportingText(error: ServerError?) {
    if (error == null) {
        Text(stringResource(R.string.server_address_hint))
    } else {
        Text(text = error.message(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun ServerError.message(): String = when (this) {
    ServerError.InvalidAddress -> stringResource(R.string.server_error_invalid_address)
    is ServerError.Api -> error.message()
}

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Preview(name = "Tablet", widthDp = 1000, heightDp = 700)
@Composable
private fun ServerScreenPreview() {
    LirelleTheme {
        ServerScreen(
            state = ServerUiState(address = "books.example.com"),
            onAddressChange = {},
            onContinue = {},
            onCleartextConfirmed = {},
            onCleartextDismissed = {},
        )
    }
}
