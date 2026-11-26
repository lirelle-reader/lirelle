package app.tfdn.lirelle.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.UserAgent
import app.tfdn.lirelle.core.session.ServerKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val serverUrl: String = "",
    val isCleartext: Boolean = false,
    val isLoadingOptions: Boolean = true,
    val options: LoginOptions? = null,
    /** What the account is named by: a username or an email address, as the server takes it. */
    val username: String = "",
    val password: String = "",
    /** A sign-in is being sent, or the browser or a provider's page is open for OIDC. */
    val isBusy: Boolean = false,
    val error: ApiError? = null,
) {
    /** True when a password sign-in can be sent. */
    val canSubmit: Boolean get() = !isBusy && username.isNotBlank() && password.isNotEmpty()

    /**
     * True when there is no server to sign in to: none is remembered, and the user must go through the Server
     * screen first — or the profile that was to sign in again is no longer on the device.
     */
    val needsServer: Boolean get() = serverUrl.isBlank()

    /** Never prints the password: this text ends up in logs and in failed assertions. */
    override fun toString(): String =
        "SignInUiState(serverUrl=$serverUrl, isCleartext=$isCleartext, isLoadingOptions=$isLoadingOptions, " +
            "options=$options, username=$username, password=${if (password.isEmpty()) "<empty>" else "<redacted>"}, " +
            "isBusy=$isBusy, error=$error)"
}

/** What an Auth Tab answered, free of Android types. */
sealed interface AuthTabResult {
    /** The provider redirected to [redirectUri]: the sign-in can be completed with it. */
    data class Redirected(val redirectUri: String) : AuthTabResult

    /** The user closed the tab before the provider answered. */
    data object Cancelled : AuthTabResult

    /** The browser could not vouch for the redirect, or answered something unknown. */
    data object Failed : AuthTabResult
}

/**
 * The sign-in step: the methods the server offers, then a password or OIDC sign-in, which ends in the session
 * saved — its account's profile is then the current one.
 *
 * About no profile: it reads the server from what the device remembers, or is given it, and never from who is
 * signed in. It is the same whether nobody is, or the sign-in is shown over a profile — which may change under
 * it, and of which it holds nothing.
 *
 * Each sign-in goes to the kind of server it is for: the one remembered with the address, or the profile's own.
 * An address remembered with a kind this version does not know is no server to sign in to: the Server screen
 * checks it again.
 *
 * @param server the server to sign in to when it is not the remembered one: that of a profile signing in
 *   again. The remembered address is not touched to sign in there; once that profile has signed in, its
 *   server is the one remembered, as after any sign-in. Null for the remembered one.
 * @param kind the kind of [server]: the profile's. Null with the remembered server, whose kind is remembered.
 */
@HiltViewModel(assistedFactory = SignInViewModel.Factory::class)
class SignInViewModel @AssistedInject constructor(
    private val auth: AuthRepository,
    private val redirects: OidcRedirectRelay,
    /** How the app names itself: the provider's WebView adds it to its own agent. */
    val userAgent: UserAgent,
    @Assisted server: String?,
    @Assisted kind: ServerKind?,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(server: String?, kind: ServerKind?): SignInViewModel
    }

    /** The kind every sign-in of this step goes to; null only when there is no server to sign in to. */
    private val kind: ServerKind? = if (server != null) kind else auth.serverKind.value

    private val mutableState: MutableStateFlow<SignInUiState>
    val state: StateFlow<SignInUiState>

    private val browserChannel = Channel<String>(Channel.BUFFERED)

    /** Authorization URLs to open in a browser tab. */
    val browserUrls: Flow<String> = browserChannel.receiveAsFlow()

    private val signedInChannel = Channel<Unit>(Channel.BUFFERED)

    /**
     * Emits once a sign-in has succeeded and its session is saved. Nothing is to be done with it while nobody
     * was signed in — the signed-in level takes the place of this screen; shown over a profile, the screen
     * leaves when that profile is still the current one.
     */
    val signedIn: Flow<Unit> = signedInChannel.receiveAsFlow()

    /**
     * The page of a provider that signs in through a WebView of the app ([ProviderFlow.WebView]), while it is
     * open. The form stays busy meanwhile; closed by the user, it is as it was, with nothing to say.
     */
    val providerPage = ProviderPageTurn(
        scope = viewModelScope,
        finish = { page, cookies ->
            // Signed in, the form stays busy until the screen leaves, as after a password ([onSignIn]).
            when (val result = auth.completeWithSession(page.serverUrl, page.kind, cookies)) {
                is ApiResult.Success -> signedInChannel.send(Unit)
                is ApiResult.Failure -> mutableState.update { it.copy(isBusy = false, error = result.error) }
            }
        },
        closed = { error -> mutableState.update { it.copy(isBusy = false, error = error) } },
    )

    /** The OIDC sign-in the browser is open for; see [OidcTurn] for the two ways back. */
    private val oidc = OidcTurn(auth::hasPendingOidc)
    private var cancellation: Job? = null

    init {
        // Without its kind, an address is not one to sign in to.
        val serverUrl = (server ?: auth.serverUrl.value).takeIf { this.kind != null }.orEmpty()
        mutableState = MutableStateFlow(
            SignInUiState(
                serverUrl = serverUrl,
                isCleartext = serverUrl.startsWith("http://"),
            ),
        )
        state = mutableState.asStateFlow()
        loadOptions()
        viewModelScope.launch {
            // The activity accepts this link from any app or page. One that is not the answer of the sign-in
            // that waits is dropped: that sign-in goes on waiting for its own, and no error is shown for what
            // was not its answer.
            redirects.redirects.collect { if (auth.answersPendingOidc(it)) onRedirect(it) }
        }
    }

    /** Loads the sign-in methods of the server. Also the retry action. */
    fun loadOptions() {
        val kind = kind
        if (state.value.needsServer || kind == null) {
            // Nothing to ask and nothing to retry: the screen sends the user to the Server screen.
            mutableState.update { it.copy(isLoadingOptions = false) }
            return
        }
        mutableState.update { it.copy(isLoadingOptions = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.loginOptions(state.value.serverUrl, kind)) {
                is ApiResult.Success ->
                    mutableState.update { it.copy(isLoadingOptions = false, options = result.value) }
                is ApiResult.Failure ->
                    mutableState.update { it.copy(isLoadingOptions = false, error = result.error) }
            }
        }
    }

    fun onUsernameChange(username: String) {
        mutableState.update { it.copy(username = username, error = null) }
    }

    fun onPasswordChange(password: String) {
        mutableState.update { it.copy(password = password, error = null) }
    }

    fun onSignIn() {
        val kind = kind
        // Also reached from the keyboard, which does not know the button is disabled.
        if (!state.value.canSubmit || kind == null) return
        mutableState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val current = state.value
            val result = auth.signInWithPassword(current.serverUrl, kind, current.username.trim(), current.password)
            mutableState.update {
                when (result) {
                    // The password is not needed any more: it must not stay in memory. The form stays busy
                    // until the screen leaves: the signed-in level comes only once the profile before has
                    // closed and this one's objects are built, which may take seconds after a sign-out
                    // during playback, and an idle form would invite a second sign-in meanwhile.
                    is ApiResult.Success -> it.copy(password = "")
                    is ApiResult.Failure -> it.copy(isBusy = false, error = result.error)
                }
            }
            if (result is ApiResult.Success) signedInChannel.send(Unit)
        }
    }

    fun onOidc(provider: OidcProvider) {
        val kind = kind
        if (state.value.isBusy || kind == null) return
        mutableState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.beginOidc(state.value.serverUrl, kind, provider)) {
                is ApiResult.Success -> when (provider.signsInBy) {
                    ProviderFlow.WebView -> providerPage.open(
                        ProviderPage(state.value.serverUrl, kind, result.value, provider.displayName),
                    )
                    ProviderFlow.Browser -> {
                        oidc.begin()
                        browserChannel.send(result.value)
                    }
                }
                is ApiResult.Failure -> mutableState.update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }

    /**
     * Called once the browser is in front. [authTab] tells whether it opened an Auth Tab, whose
     * answer comes through [onAuthTabResult], or a plain Custom Tab, whose redirect comes
     * through the [OidcRedirectRelay].
     */
    fun onBrowserOpened(authTab: Boolean) = oidc.browserOpened(authTab)

    /**
     * Whether the sign-in in progress was opened in an Auth Tab, as told to [onBrowserOpened];
     * null when this view model opened no browser. The route reads the browser's answer with
     * this rather than by asking again what the default browser can do: the browser may have
     * been updated or replaced while the tab was open.
     */
    val openedInAuthTab: Boolean? get() = oidc.openedInAuthTab

    /**
     * Called with the answer of an Auth Tab. The route keeps the answers of a Custom Tab away
     * from here, going by [openedInAuthTab]: a Custom Tab reports "cancelled" whatever happened.
     *
     * Whichever of this and the relay comes first takes the sign-in; the other finds nothing
     * left to act on. The answer is also used by a view model that did not start the sign-in:
     * the app may have been killed while the browser was in front.
     */
    fun onAuthTabResult(result: AuthTabResult) {
        viewModelScope.launch {
            val error = when (result) {
                is AuthTabResult.Redirected -> {
                    if (auth.answersPendingOidc(result.redirectUri)) return@launch onRedirect(result.redirectUri)
                    // The tab has answered and is closed: no other answer can come. Not the answer of this
                    // sign-in, it ends it, refused.
                    ApiError.OidcRefused
                }
                // The user closed the tab: no need to wait for a redirect that cannot come.
                AuthTabResult.Cancelled -> ApiError.OidcCancelled
                AuthTabResult.Failed -> ApiError.OidcRefused
            }
            oidc.settle {
                cancellation?.cancel()
                auth.cancelOidc()
                mutableState.update { it.copy(isBusy = false, error = error) }
            }
        }
    }

    /**
     * Called when the screen comes back to the foreground. If a Custom Tab was open for OIDC
     * and no redirect follows shortly, the user closed it: the sign-in is cancelled. An Auth
     * Tab always answers by itself, so nothing is assumed while one is open.
     */
    fun onBrowserReturned() {
        if (!oidc.closedTabMeansCancelled || cancellation?.isActive == true) return
        cancellation = viewModelScope.launch {
            delay(RedirectGraceMillis)
            oidc.settle(orLeftOnDisk = false) {
                auth.cancelOidc()
                mutableState.update { it.copy(isBusy = false, error = ApiError.OidcCancelled) }
            }
        }
    }

    /** Called when no browser could open the authorization URL: the sign-in cannot go on. */
    fun onBrowserUnavailable() {
        oidc.drop()
        cancellation?.cancel()
        mutableState.update { it.copy(isBusy = false, error = ApiError.BrowserUnavailable) }
        viewModelScope.launch { auth.cancelOidc() }
    }

    private suspend fun onRedirect(redirectUri: String) {
        // The relay keeps the last redirect: it is acted on only when a sign-in waits for it.
        oidc.settle {
            cancellation?.cancel()
            mutableState.update { it.copy(isBusy = true, error = null) }
            val result = auth.completeOidc(redirectUri)
            // Signed in, the form stays busy until the screen leaves, as after a password ([onSignIn]).
            when (result) {
                is ApiResult.Success -> signedInChannel.send(Unit)
                is ApiResult.Failure -> mutableState.update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }

    private companion object {
        /** The redirect intent reaches the app around the same time the screen resumes. */
        const val RedirectGraceMillis = 1_500L
    }
}

/**
 * The sign-in screens shown over a profile are being left — the system's Back, the bar's arrow: whatever this
 * step has in flight ends here, and it starts nothing more. It lives until the screens have gone, and a
 * password accepted, or a redirect exchanged, in that time would otherwise still save its session and make
 * its profile the current one after the user turned back.
 *
 * The limit: a sign-in the server has already answered, whose session is being saved or is saved, stands —
 * its profile is the current one. And a request ended once the server had opened the session leaves that
 * session on the server, unused, until it expires there.
 */
fun SignInViewModel.onLeft() = viewModelScope.cancel()
