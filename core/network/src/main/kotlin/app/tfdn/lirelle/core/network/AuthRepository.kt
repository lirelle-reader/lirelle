package app.tfdn.lirelle.core.network

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An identity provider the server offers to sign in with.
 *
 * @property signsInBy how the app goes through the provider's pages.
 */
data class OidcProvider(
    val slug: String,
    val displayName: String,
    val clientId: String,
    val scopes: String,
    val signsInBy: ProviderFlow = ProviderFlow.Browser,
)

/** How a provider's pages are shown to sign in. */
enum class ProviderFlow {
    /** In the browser (an Auth Tab or a Custom Tab), which comes back to the app through its redirect. */
    Browser,

    /** In a WebView of the app, which reads the end of the flow itself. */
    WebView,
}

/**
 * The start of a sign-in through a provider: the [url] to open, and the [redirectScheme] its answer comes back to the
 * app on — the scheme of the return address the server took, which the browser's Auth Tab must watch for: one that
 * watches another lets the answer go by, as a link the system offers to open. Null for a provider shown in a WebView
 * of the app ([ProviderFlow.WebView]), whose flow never comes back through a redirect.
 */
data class OidcStart(val url: String, val redirectScheme: String?) {
    /** Only the host of [url]: its query holds the sign-in's state, and this text may end up in logs. */
    override fun toString(): String =
        "OidcStart(host=${url.toHttpUrlOrNull()?.host ?: "<redacted>"}, redirectScheme=$redirectScheme)"
}

/** What the account is named by when it signs in with a password: the field's label, keyboard and autofill. */
enum class SignInIdentifier { Username, Email }

/**
 * @property identifier what the server takes with the password.
 * @property keyEntry the user may paste a key they made on the server.
 */
data class LoginOptions(
    val passwordEnabled: Boolean,
    val oidcProviders: List<OidcProvider>,
    val identifier: SignInIdentifier = SignInIdentifier.Username,
    val keyEntry: Boolean = false,
)

/**
 * Everything the UI needs to sign in and to sign out: what happens before a profile exists, and what ends
 * one. What is asked of the server for an account that is signed in is its profile's [AccountRepository].
 *
 * One entry for the screens, whatever the kind of server: the address is asked of each kind in turn, and a
 * sign-in goes to the kind it is for — the one detected at the address, or the profile's own when it signs in
 * again.
 */
interface AuthRepository {
    val sessionState: StateFlow<SessionState>

    /**
     * The server's address remembered for the next sign-in, null while none was: what the sign-in screens
     * offer and sign in to, whether nobody is signed in or a profile is added over the current one.
     */
    val serverUrl: StateFlow<String?>

    /**
     * The kind of server at [serverUrl]: detected when it was checked, or the kind of the profile last signed in
     * to or out of there. Null while no address is remembered, or when the kind remembered is not one this
     * version knows: the address is then to be checked again.
     */
    val serverKind: StateFlow<ServerKind?>

    /**
     * Asks each kind of server in turn whether [serverUrl] is one of its own and remembers it, with that kind,
     * whoever is signed in. When the server redirected the check, the address that answered is remembered
     * instead: read it from [AuthRepository.serverUrl] rather than reusing [serverUrl] for the calls that follow.
     * [ApiError.UnknownServer] when no kind knows it.
     */
    suspend fun connectToServer(serverUrl: String): ApiResult<Unit>

    suspend fun loginOptions(serverUrl: String, kind: ServerKind): ApiResult<LoginOptions>

    /** [identifier] is what [LoginOptions.identifier] says the server takes. */
    suspend fun signInWithPassword(
        serverUrl: String,
        kind: ServerKind,
        identifier: String,
        password: String,
    ): ApiResult<Unit>

    /**
     * Signs in with [key], a key the user made on the server and pasted ([LoginOptions.keyEntry]): the profile
     * keeps it as its credential. [ApiError.KeyRefused] when the server does not know it.
     */
    suspend fun signInWithKey(serverUrl: String, kind: ServerKind, key: String): ApiResult<Unit>

    /**
     * Prepares an OIDC sign-in and returns where it starts: the URL to open, in the browser — with the scheme its
     * redirect comes back on — or in a WebView of the app for a provider that signs in that way
     * ([OidcProvider.signsInBy]).
     */
    suspend fun beginOidc(serverUrl: String, kind: ServerKind, provider: OidcProvider): ApiResult<OidcStart>

    /**
     * Finishes the sign-in of a provider shown in a WebView ([ProviderFlow.WebView]) once its page has reached the
     * server's own: [cookies] are those the WebView holds for [serverUrl], as it would send them there (a `Cookie`
     * header), and carry the session the provider's sign-in opened. The server's [kind] turns that session into
     * the profile's own credential and ends it. Nothing of the attempt outlives the app: a WebView whose process
     * was stopped is begun again.
     */
    suspend fun completeWithSession(serverUrl: String, kind: ServerKind, cookies: String): ApiResult<Unit>

    /**
     * Finishes the OIDC sign-in with the URI the browser redirected to, which uses that sign-in up whatever
     * comes of it. Refused, the sign-in left waiting, when [redirectUri] is not its answer
     * ([answersPendingOidc]); refused too when the sign-in was begun too long ago.
     */
    suspend fun completeOidc(redirectUri: String): ApiResult<Unit>

    /**
     * Tells whether an OIDC sign-in is waiting for its redirect. It survives the app being
     * killed while the browser is in front, so a redirect that arrives when this is false was
     * not asked for.
     */
    suspend fun hasPendingOidc(): Boolean

    /**
     * Tells whether [redirectUri] is the answer of the OIDC sign-in that waits: it carries the state that
     * sign-in was begun with. The app is sent this link by whatever asks, a page or another app: one that
     * answers nothing is neither a failure of the sign-in in progress nor a reason to end it.
     */
    suspend fun answersPendingOidc(redirectUri: String): Boolean

    /** Forgets a pending OIDC sign-in, for example when the user closed the browser. */
    suspend fun cancelOidc()

    /**
     * Signs out of the profile [profileId], the one the user was asked about: its session is ended on the
     * server when possible, and always locally, by the profile's own kind of server.
     *
     * The profile is named because the answer comes after the question: another profile may have become the
     * current one meanwhile — a sign-in ending, a first sign-out that got there before — and it was not the one
     * asked about. Nothing is done then, neither on the device nor on the server; asked twice for the same
     * profile, this signs out once.
     */
    suspend fun signOut(profileId: String)

    /**
     * Ends on [serverUrl] the [credential] of a profile of [kind] that was removed while it slept: no profile is
     * open, and its credential is handed over. Each kind does as it must: BookOrbit sends nothing (its session
     * expires on its own), Komga revokes the key with the key itself (a key never expires). At best, and soon
     * given up: the profile is gone from the device already, whatever the server answers. Nothing is done for a
     * kind without its sign-in here.
     */
    suspend fun endCredential(kind: ServerKind, serverUrl: String, credential: Credential)
}
