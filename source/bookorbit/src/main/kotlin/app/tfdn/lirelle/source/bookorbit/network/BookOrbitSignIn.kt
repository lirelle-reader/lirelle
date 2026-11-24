package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.Pkce
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.PendingOidcStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.awaitLoaded
import app.tfdn.lirelle.source.bookorbit.network.dto.AuthUserDto
import app.tfdn.lirelle.source.bookorbit.network.dto.LoginRequest
import app.tfdn.lirelle.source.bookorbit.network.dto.NativeAuthDto
import app.tfdn.lirelle.source.bookorbit.network.dto.OidcCallbackRequest
import app.tfdn.lirelle.source.bookorbit.network.dto.RefreshRequest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * How long an OIDC sign-in waits for its redirect: the time to sign in at the provider, with room to spare.
 * Past it the redirect is refused: a browser tab left open and finished much later must not sign anybody in,
 * nor change who is current, long after the user moved on.
 */
private val PendingOidcLifetime: Duration = Duration.ofMinutes(10)

/**
 * Whether this sign-in was begun too long before [now] to be completed. One that does not say when it was
 * begun — stored before the time was kept — is. So is one begun later than now by more than a sign-in lasts:
 * the clock was set back, and nothing tells how old it is.
 */
internal fun PendingOidcRequest.expired(now: Instant): Boolean {
    val begunAt = begunAtMillis ?: return true
    return Duration.between(Instant.ofEpochMilli(begunAt), now).abs() > PendingOidcLifetime
}

/**
 * Signing in to a BookOrbit server — a username and a password, or an identity provider through the browser
 * (PKCE, back by the app's `bookorbit://` redirect) — and out of a BookOrbit profile. Each sign-in opens a session
 * of its own for the device, saved as a BookOrbit profile.
 */
internal class BookOrbitSignIn(
    private val store: SessionStore,
    private val pendingOidc: PendingOidcStore,
    private val publicApi: ApiProvider<PublicApi>,
    private val errors: ErrorMapper,
    private val deviceLabel: DeviceLabelProvider,
    private val now: () -> Instant = Instant::now,
) : SourceSignIn {

    override suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions> = errors.call(CallKind.Other) {
        val dto = publicApi.forServer(serverUrl).loginOptions()
        LoginOptions(
            passwordEnabled = dto.passwordLoginEnabled,
            oidcProviders = dto.oidcProviders
                .filter { it.enabled }
                .map { OidcProvider(it.slug, it.displayName, it.clientId, it.scopes, ProviderFlow.Browser) },
            identifier = SignInIdentifier.Username,
        )
    }

    override suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit> =
        errors.call(CallKind.Login) {
            val dto = publicApi.forServer(serverUrl)
                .login(LoginRequest(username = identifier, password = password, deviceLabel = deviceLabel.label()))
            if (dto.user.isDefaultPassword) {
                // Every other route would answer 403 until the password is changed on the web.
                publicApi.closeSession(serverUrl, dto.refreshToken)
                throw ApiErrorException(ApiError.DefaultPasswordMustChange)
            }
            store.saveAndEndReplaced(dto.toSession(serverUrl), publicApi)
        }

    override suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<String> =
        errors.call(CallKind.Other) {
            val dto = publicApi.forServer(serverUrl).oidcState(provider.slug)
            val verifier = Pkce.randomToken()
            val nonce = Pkce.randomToken()
            val begunAt = now().toEpochMilli()
            pendingOidc.save(PendingOidcRequest(serverUrl, provider.slug, dto.state, verifier, nonce, begunAt))
            buildAuthorizationUrl(
                authorizationEndpoint = dto.authorizationEndpoint,
                clientId = provider.clientId,
                scopes = provider.scopes,
                state = dto.state,
                nonce = nonce,
                codeChallenge = Pkce.challenge(verifier),
            )
        }

    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> {
        val redirect = OidcRedirect.parse(redirectUri)
        // Looked at before it is used up: anything can send this link, and only the answer of the sign-in that
        // waits may end it. Any other is refused with that sign-in left as it is, for its own redirect.
        val pending = redirect.state?.let { state -> pendingOidc.takeWhen { it.state == state } }
        if (pending == null || redirect !is OidcRedirect.Code || pending.expired(now())) {
            return ApiResult.Failure(ApiError.OidcRefused)
        }
        return errors.call(CallKind.OidcCallback) {
            val dto = publicApi.forServer(pending.serverUrl).oidcCallback(
                OidcCallbackRequest(
                    code = redirect.code,
                    codeVerifier = pending.codeVerifier,
                    redirectUri = OidcRedirectUri,
                    nonce = pending.nonce,
                    state = pending.state,
                    deviceLabel = deviceLabel.label(),
                ),
            )
            // No default-password check here: the server lets a session opened through the
            // identity provider in whatever the state of the account's local password.
            store.saveAndEndReplaced(dto.toSession(pending.serverUrl), publicApi)
        }
    }

    /** BookOrbit's providers sign in through the browser, whose redirect [completeOidc] takes: never a WebView. */
    override suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit> =
        ApiResult.Failure(ApiError.OidcRefused)

    override suspend fun hasPendingOidc(): Boolean = pendingOidc.isPending()

    override suspend fun answersPendingOidc(redirectUri: String): Boolean {
        val state = OidcRedirect.parse(redirectUri).state ?: return false
        return pendingOidc.peek()?.state == state
    }

    override suspend fun cancelOidc() {
        pendingOidc.clear()
    }

    override suspend fun signOut(profileId: String) {
        store.awaitLoaded()
        // The profile and its session in one read: the session handed back to the server below is the one of
        // the profile removed here, whoever became current meanwhile. Another profile than the one asked
        // about is nobody's to sign out of: neither removed, nor its session closed on its server. Nor is a
        // profile of another kind, whose credential this could not end.
        val profile = store.current.value?.takeIf { it.id == profileId && it.kind == ServerKind.BookOrbit } ?: return
        // The removal and the logout to the end together: the server is still told when the caller goes away, as
        // the screen does on sign-out — torn down by the profile switch the removal sets off.
        withContext(NonCancellable) {
            // Locally first: whatever happens to the call below, the user is signed out, and for
            // the reason they chose even if the logout makes a renewal in flight fail.
            val removed = store.clear(profile.id, SignOutReason.UserRequest)
            // Something got there first — a second tap, a sign-in that made another profile current: the profile
            // asked about is no longer the current one, and there is nothing left to tell the server. (A session
            // that expires removes nobody: its profile is kept, marked as needing a sign-in.)
            if (!removed) return@withContext
            val session = profile.session
            val tokens = (session.credential as? Credential.SessionTokens)?.tokens ?: return@withContext
            publicApi.closeSession(session.serverUrl, tokens.refreshToken)
        }
    }

    /**
     * Nothing is sent for the session of a profile removed while it slept, as before there were kinds: it expires
     * on its own on the server, and its tokens go with the profile's folders.
     */
    override suspend fun endCredential(serverUrl: String, credential: Credential) = Unit
}

/**
 * Saves [session], a BookOrbit profile's, then ends on its server the session of the same account that it
 * replaced on this device.
 *
 * Adding an account that already has a profile here signs in anew: the server opens a second session for the
 * device and keeps the first one valid until it expires, unless it is told. It is told with the tokens the profile
 * held, once the new session is saved, and at best: whatever it answers — out of reach, the session already gone —
 * the sign-in stands. Nothing is sent for a session that the server had already refused (the profile waited for a
 * sign-in): it is ended there already. Nor for the very session saved.
 */
private suspend fun SessionStore.saveAndEndReplaced(session: Session, publicApi: ApiProvider<PublicApi>) {
    val replaced = save(session, ServerKind.BookOrbit) ?: return
    val old = replaced.credential as? Credential.SessionTokens ?: return
    if (replaced.refused || old.isSameSignIn(session.credential)) return
    // Told even when the sign-in screen is left meanwhile: the new session is saved already.
    withContext(NonCancellable) { publicApi.closeSession(session.serverUrl, old.tokens.refreshToken) }
}

/** Best effort: the local session is dropped whatever the server answers. */
@Suppress("TooGenericExceptionCaught")
private suspend fun ApiProvider<PublicApi>.closeSession(serverUrl: String, refreshToken: String) {
    try {
        forServer(serverUrl).logout(RefreshRequest(refreshToken))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Unreachable server, refusal, or anything else: the session is gone locally and
        // expires on its own on the server.
    }
}

/** The account as the app keeps it: BookOrbit numbers its accounts, the app names them by that number's text. */
internal fun AuthUserDto.toProfile() = UserProfile(
    id = id.toString(),
    username = username,
    name = name,
    email = email,
    canDownload = canDownload,
    avatarUrl = avatarUrl,
)

private fun NativeAuthDto.toSession(serverUrl: String) = Session(
    serverUrl = serverUrl,
    credential = Credential.SessionTokens(
        sessionId = sessionId,
        tokens = Tokens(
            accessToken = accessToken,
            accessTokenExpiresAt = Instant.parse(accessTokenExpiresAt),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = Instant.parse(refreshTokenExpiresAt),
        ),
    ),
    user = user.toProfile(),
)
