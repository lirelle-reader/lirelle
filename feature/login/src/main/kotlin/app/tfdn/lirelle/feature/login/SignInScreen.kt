package app.tfdn.lirelle.feature.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.SignInIdentifier

/**
 * @param onChangeServer leaves for the server step; also asked when there is no server to sign in to.
 * @param purpose what the sign-in is for: its heading, and whether the server can be changed.
 * @param again the profile that signs in again, on its own server; null for the other purposes, and when it
 *   is no longer on the device — there is then no server, and [onChangeServer] is asked.
 * @param onSignedIn told once a sign-in has succeeded and its session is saved.
 * @param onProviderPage asked to show the page of a provider that signs in through a WebView of the app
 *   ([ProviderPageRoute], over this step), once it is open.
 */
@Composable
fun SignInRoute(
    onChangeServer: () -> Unit,
    purpose: SignInPurpose = SignInPurpose.First,
    again: ProfileLabel? = null,
    onSignedIn: () -> Unit = {},
    onProviderPage: () -> Unit = {},
    viewModel: SignInViewModel = signInViewModel(purpose, again),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val providerPage by viewModel.providerPage.page.collectAsStateWithLifecycle()
    val showProviderPage by rememberUpdatedState(onProviderPage)
    LaunchedEffect(providerPage != null) {
        if (providerPage != null) showProviderPage()
    }

    // Registered under a saved key: the answer still finds its way here when the activity,
    // or the whole process, was recreated while the browser was in front.
    val contract = remember { AuthTabIntent.AuthenticateUserResultContract() }
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        // What was opened decides how the answer reads, not what the browser can do by now.
        // Only a view model that opened nothing, after the process was killed with the
        // browser in front, has to ask the browser again.
        val fromAuthTab = viewModel.openedInAuthTab ?: (authTabBrowser(context) != null)
        authTabResultOf(result.resultCode, result.resultUri?.toString(), fromAuthTab)
            ?.let(viewModel::onAuthTabResult)
    }
    LaunchedEffect(viewModel, launcher) {
        viewModel.browserUrls.collect { start ->
            val browser = authTabBrowser(context)
            if (openForSignIn(launcher, start, browser)) {
                viewModel.onBrowserOpened(authTab = browser != null)
            } else {
                viewModel.onBrowserUnavailable()
            }
        }
    }
    LaunchedEffect(state.needsServer) {
        if (state.needsServer) onChangeServer()
    }
    val signedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(viewModel) { viewModel.signedIn.collect { signedIn() } }
    // Shown over a profile: the screens say when they are left, and a sign-in in flight ends there.
    val leaving = LocalSignInLeaving.current
    DisposableEffect(leaving, viewModel) {
        val onLeft = viewModel::onLeft
        leaving?.listen(onLeft)
        onDispose { leaving?.forget(onLeft) }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onBrowserReturned()
        onPauseOrDispose { }
    }

    SignInScreen(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSignIn = viewModel::onSignIn,
        onOidc = viewModel::onOidc,
        onChangeServer = onChangeServer,
        onRetry = viewModel::loadOptions,
        purpose = purpose,
        again = again,
        onKeyChange = viewModel.pastedKey::onChange,
        onKeySignIn = viewModel.pastedKey::signIn,
    )
}

/**
 * The sign-in step's view model, held by [owner]: the step's own, or the step under the provider's page. Made for
 * [purpose] the first time — on the profile's own server and kind to sign in again, never the remembered ones;
 * none when the profile is gone.
 */
@Composable
internal fun signInViewModel(
    purpose: SignInPurpose,
    again: ProfileLabel?,
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { "No view model store" },
): SignInViewModel = hiltViewModel<SignInViewModel, SignInViewModel.Factory>(
    viewModelStoreOwner = owner,
    creationCallback = { factory ->
        when (purpose) {
            is SignInPurpose.Again -> factory.create(again?.serverUrl.orEmpty(), again?.kind)
            else -> factory.create(null, null)
        }
    },
)

/**
 * @param onKeyChange and [onKeySignIn]: the key the user pastes and its button, on a server that takes one.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onOidc: (OidcProvider) -> Unit,
    onChangeServer: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    purpose: SignInPurpose = SignInPurpose.First,
    again: ProfileLabel? = null,
    onKeyChange: (String) -> Unit = {},
    onKeySignIn: () -> Unit = {},
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
            SignInHeading(purpose, firstTitle = stringResource(R.string.sign_in_title), again = again)
            // A profile signing in again does so on its own server, which its heading shows.
            if (purpose !is SignInPurpose.Again) {
                ServerLine(serverUrl = state.serverUrl, canChange = !state.isBusy, onChangeServer = onChangeServer)
            }
            if (state.isCleartext) {
                Text(
                    text = stringResource(R.string.sign_in_cleartext_notice),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let { error ->
                Text(
                    text = error.message(state.options?.identifier ?: SignInIdentifier.Username),
                    // Announced when it appears: nothing else tells a screen reader user.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                // The route is leaving for the Server screen: there is nothing to offer here.
                state.needsServer -> Unit
                state.isLoadingOptions -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.options == null -> OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    SingleLineLabel(stringResource(R.string.sign_in_retry))
                }
                else -> SignInMethods(
                    state = state,
                    options = state.options,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onSignIn = onSignIn,
                    onOidc = onOidc,
                    onKeyChange = onKeyChange,
                    onKeySignIn = onKeySignIn,
                )
            }
        }
    }
}

/**
 * The server address with the button that changes it at the end of the line. When both do not
 * fit on one line the button goes below, so a long address or large text squeezes neither.
 */
@Composable
private fun ServerLine(serverUrl: String, canChange: Boolean, onChangeServer: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = serverUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onChangeServer, enabled = canChange) {
            SingleLineLabel(stringResource(R.string.sign_in_change_server))
        }
    }
}

/**
 * What the server offers, in an order that does not change: the identifier, the password and their button; each
 * provider's button; then, on a server that takes one, the key the user pastes ([KeySignInBlock]).
 */
@Composable
private fun SignInMethods(
    state: SignInUiState,
    options: LoginOptions,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onOidc: (OidcProvider) -> Unit,
    onKeyChange: (String) -> Unit,
    onKeySignIn: () -> Unit,
) {
    val othersOffered = options.passwordEnabled || options.oidcProviders.isNotEmpty()
    if (!othersOffered && !options.keyEntry) {
        Text(stringResource(R.string.sign_in_no_method))
        return
    }
    if (options.passwordEnabled) PasswordForm(state, options.identifier, onUsernameChange, onPasswordChange, onSignIn)
    options.oidcProviders.forEach { provider ->
        OutlinedButton(
            onClick = { onOidc(provider) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SingleLineLabel(stringResource(R.string.sign_in_with_provider, provider.displayName))
        }
    }
    if (options.keyEntry) KeySignInBlock(state, underOthers = othersOffered, onKeyChange, onKeySignIn)
}

/** The identifier the server takes — a username or an email address —, the password, and the button that sends them. */
@Composable
private fun PasswordForm(
    state: SignInUiState,
    identifier: SignInIdentifier,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val byEmail = identifier == SignInIdentifier.Email
    OutlinedTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = if (byEmail) ContentType.EmailAddress else ContentType.Username },
        label = { Text(stringResource(if (byEmail) R.string.sign_in_email else R.string.sign_in_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (byEmail) KeyboardType.Email else KeyboardType.Unspecified,
            imeAction = ImeAction.Next,
        ),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
        label = { Text(stringResource(R.string.sign_in_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSignIn() }),
    )
    Button(onClick = onSignIn, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
        SingleLineLabel(stringResource(R.string.sign_in_submit))
    }
}

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Preview(name = "Tablet", widthDp = 1000, heightDp = 700)
@Composable
private fun SignInScreenPreview() {
    LirelleTheme {
        SignInScreen(
            state = SignInUiState(
                serverUrl = "https://books.example.com",
                isLoadingOptions = false,
                options = LoginOptions(
                    passwordEnabled = true,
                    oidcProviders = listOf(OidcProvider("authentik", "Authentik", "client", "openid")),
                ),
            ),
            onUsernameChange = {},
            onPasswordChange = {},
            onSignIn = {},
            onOidc = {},
            onChangeServer = {},
            onRetry = {},
        )
    }
}
