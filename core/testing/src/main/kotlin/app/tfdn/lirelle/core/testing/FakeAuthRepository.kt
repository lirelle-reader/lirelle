package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Scriptable [AuthRepository]. Set the `…Result` fields, then read [calls]. */
@Suppress("TooManyFunctions") // One function per operation of the contract.
class FakeAuthRepository : AuthRepository {
    val session = MutableStateFlow<SessionState>(SessionState.SignedOut(serverUrl = null, reason = null))
    override val sessionState: StateFlow<SessionState> = session

    /** The address remembered for the next sign-in: set it to play one typed earlier; [connectToServer] does. */
    override val serverUrl = MutableStateFlow<String?>(null)

    /** The kind of [serverUrl]: a BookOrbit server's unless a test plays another; [connectToServer] sets it. */
    override val serverKind = MutableStateFlow<ServerKind?>(ServerKind.BookOrbit)

    /** The kind [connectToServer] detects at the address. */
    var connectKind: ServerKind = ServerKind.BookOrbit

    /** The kind each sign-in call was sent to, in order: options, password, OIDC begun, a provider's page ended. */
    val kinds = mutableListOf<ServerKind>()

    var connectResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var loginOptionsResult: ApiResult<LoginOptions> =
        ApiResult.Success(LoginOptions(passwordEnabled = true, oidcProviders = emptyList()))
    var signInResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var beginOidcResult: ApiResult<String> = ApiResult.Success("https://auth.example.com/authorize?state=state-1")
    var completeOidcResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var completeWithSessionResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /**
     * Whether an OIDC sign-in waits for its redirect. Set by [beginOidc], reset by
     * [completeOidc] and [cancelOidc]; set it by hand to play a request left on disk by a
     * previous process.
     */
    var oidcPending = false

    /** The state the sign-in that waits was begun with, as [beginOidcResult] gives it to the browser. */
    var oidcState = "state-1"

    /** The server the sign-in that waits was begun on; null when [oidcPending] was set by hand. */
    private var oidcServer: String? = null
    private var oidcKind: ServerKind = ServerKind.BookOrbit

    /**
     * When set, every call is recorded, then waits for this before answering. Lets a test
     * look at the state while a call is in flight, then let it finish with `complete(Unit)`.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** One entry per call, for example `connect https://books.example.com`. */
    val calls = mutableListOf<String>()

    override suspend fun connectToServer(serverUrl: String): ApiResult<Unit> {
        calls += "connect $serverUrl"
        gate?.await()
        if (connectResult is ApiResult.Success) remember(serverUrl, connectKind)
        return connectResult
    }

    override suspend fun loginOptions(serverUrl: String, kind: ServerKind): ApiResult<LoginOptions> {
        calls += "loginOptions $serverUrl"
        kinds += kind
        gate?.await()
        return loginOptionsResult
    }

    override suspend fun signInWithPassword(
        serverUrl: String,
        kind: ServerKind,
        identifier: String,
        password: String,
    ): ApiResult<Unit> {
        calls += "signIn $serverUrl $identifier $password"
        kinds += kind
        gate?.await()
        if (signInResult is ApiResult.Success) signedIn(serverUrl, kind)
        return signInResult
    }

    override suspend fun beginOidc(serverUrl: String, kind: ServerKind, provider: OidcProvider): ApiResult<String> {
        calls += "beginOidc $serverUrl ${provider.slug}"
        kinds += kind
        gate?.await()
        if (beginOidcResult is ApiResult.Success) {
            oidcPending = true
            oidcServer = serverUrl
            oidcKind = kind
        }
        return beginOidcResult
    }

    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> {
        calls += "completeOidc $redirectUri"
        gate?.await()
        // As the real one: the answer of the pending request uses it up; any other redirect is refused, and
        // leaves it waiting.
        if (!answersPendingOidc(redirectUri)) return ApiResult.Failure(ApiError.OidcRefused)
        oidcPending = false
        if (completeOidcResult is ApiResult.Success) signedIn(oidcServer, oidcKind)
        return completeOidcResult
    }

    /** Recorded with the cookies handed over, which a test reads back: they are a test's own. */
    override suspend fun completeWithSession(serverUrl: String, kind: ServerKind, cookies: String): ApiResult<Unit> {
        calls += "completeWithSession $serverUrl $cookies"
        kinds += kind
        gate?.await()
        if (completeWithSessionResult is ApiResult.Success) signedIn(serverUrl, kind)
        return completeWithSessionResult
    }

    /** A plain question: neither recorded in [calls] nor held by [gate]. */
    override suspend fun hasPendingOidc(): Boolean = oidcPending

    /** A plain question too. As the real one: the redirect carries the state of the sign-in that waits. */
    override suspend fun answersPendingOidc(redirectUri: String): Boolean =
        oidcPending && stateOf(redirectUri) == oidcState

    override suspend fun cancelOidc() {
        calls += "cancelOidc"
        gate?.await()
        oidcPending = false
    }

    override suspend fun signOut(profileId: String) {
        calls += "signOut $profileId"
        gate?.await()
    }

    /** Recorded with the credential as it prints itself: a key never shows. */
    override suspend fun endCredential(kind: ServerKind, serverUrl: String, credential: Credential) {
        calls += "endCredential $kind $serverUrl $credential"
        gate?.await()
    }
}

/**
 * The `state` parameter of a redirect's query, as an OAuth redirect carries the state of the sign-in it answers.
 * As the real parser: the fragment is dropped first, the last `state` wins, and an empty one, a missing one or one
 * that cannot be decoded is null.
 *
 * Unlike the real parser, it does not check that the redirect is the app's own address, does not decode the
 * parameters' names (`st%61te` is not read as `state`), and a broken escape in another parameter does not refuse
 * the whole redirect.
 */
private fun stateOf(redirectUri: String): String? = redirectUri.substringBefore('#').substringAfter('?', "")
    .split('&')
    .lastOrNull { it.startsWith("state=") }
    ?.let { runCatching { URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }.getOrNull() }
    ?.takeIf { it.isNotEmpty() }

/** As the real one: remembered whoever is signed in, and told to a session that is signed out. */
private fun FakeAuthRepository.remember(address: String, kind: ServerKind) {
    serverKind.value = kind
    serverUrl.value = address
    if (session.value is SessionState.SignedOut) session.value = SessionState.SignedOut(address, reason = null)
}

/**
 * As the real store when a session is saved: the server signed in to is the one remembered from then on, and
 * an OIDC sign-in that still waited waits no more. The session itself is left to the test.
 */
private fun FakeAuthRepository.signedIn(server: String?, kind: ServerKind) {
    if (server != null) {
        serverKind.value = kind
        serverUrl.value = server
    }
    oidcPending = false
}
