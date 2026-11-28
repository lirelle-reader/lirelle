package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.NoPastedKey
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcStart
import app.tfdn.lirelle.core.network.PastedKeySignIn
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.PendingOidcStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.awaitLoaded
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsLoginRequest
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsStatusDto
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** What a provider's button says when the server gives it no text. */
private const val UnnamedProvider = "OpenID"

/** The sign-in methods the server announces: a name and a password, its identity provider. */
private const val LocalMethod = "local"
private const val OpenIdMethod = "openid"

/**
 * Signing in to an Audiobookshelf server, and out of an Audiobookshelf profile. By name and password, or through the
 * server's identity provider in the browser ([AbsOidc]): the session either opens is saved as an Audiobookshelf
 * profile holding its access and refresh tokens, under a sign-in id the app draws ([AbsProfiles]) — the refresh token
 * changes at every renewal, and cannot name the sign-in. The password and the old token every answer also carries
 * are kept nowhere. Never with a key the user pasted ([NoPastedKey]).
 *
 * A session the app lets go of is closed on the server at best ([logout]): the current profile's when it is signed
 * out of, the one an account signed in again replaces, and the one of a profile removed while it slept.
 */
internal class AbsSignIn(
    private val store: SessionStore,
    private val api: AbsApis<AbsPublicApi>,
    oidcApi: AbsApis<AbsOidcApi>,
    pendingOidc: PendingOidcStore,
    private val errors: AbsErrors,
    private val logout: AbsLogout,
    now: () -> Instant = Instant::now,
    newSignInId: () -> String = DrawSignInId,
) : SourceSignIn,
    PastedKeySignIn by NoPastedKey {

    private val profiles = AbsProfiles(store, logout, now, newSignInId)

    private val oidc = AbsOidc(pendingOidc, oidcApi, errors, profiles, now)

    /**
     * A name and a password when the server announces `local`, its provider in the browser when it announces
     * `openid`, named by the server's button or "OpenID"; never a pasted key. A server too old is refused here too:
     * a profile that signs in again comes here without the probe.
     */
    override suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions> = errors.call(AbsCall.Other) {
        val said = api.forServer(serverUrl).status().answer().textUpTo(MaxStatusBytes)
        if (absStatusClaims(said) == null) throw SerializationException("Not Audiobookshelf's status")
        val status = AbsJson.decodeFromString(AbsStatusDto.serializer(), said.orEmpty())
        requireRecentAbs(status.serverVersion)
        val name = status.authFormData?.authOpenIDButtonText?.takeIf { it.isNotBlank() } ?: UnnamedProvider
        val provider = OidcProvider(ProviderSlug, name, clientId = "", scopes = "", ProviderFlow.Browser)
        LoginOptions(
            passwordEnabled = LocalMethod in status.authMethods,
            oidcProviders = listOfNotNull(provider.takeIf { OpenIdMethod in status.authMethods }),
            identifier = SignInIdentifier.Username,
            keyEntry = false,
        )
    }

    override suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit> =
        errors.call(AbsCall.SignIn) {
            val user = api.forServer(serverUrl).login(AbsLoginRequest(identifier, password)).answer().user
            profiles.save(serverUrl, user)
        }

    /** The server has one provider, [ProviderSlug]: whichever [provider] is asked for, it is that one. */
    override suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<OidcStart> =
        oidc.begin(serverUrl)

    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> = oidc.complete(redirectUri)

    /** Audiobookshelf's provider signs in through the browser, whose redirect [completeOidc] takes: never a WebView. */
    override suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit> =
        ApiResult.Failure(ApiError.OidcRefused)

    override suspend fun hasPendingOidc(): Boolean = oidc.isPending()

    override suspend fun answersPendingOidc(redirectUri: String): Boolean = oidc.answers(redirectUri)

    override suspend fun cancelOidc() = oidc.cancel()

    /**
     * Removes the profile [profileId] when it is the current one and an Audiobookshelf profile, then closes its
     * session on the server; any other is left as it is, neither removed nor its credential sent anywhere.
     *
     * Locally first: whatever the server answers, or if it does not, the user is signed out. Nothing is sent when
     * something got there first — a second tap, a sign-in that made another profile current. The removal and the
     * logout to the end together, even when the caller goes away: the profile switch the removal sets off tears the
     * screen down.
     */
    override suspend fun signOut(profileId: String) {
        store.awaitLoaded()
        val profile = store.current.value?.takeIf { it.id == profileId && it.kind == ServerKind.Audiobookshelf }
            ?: return
        withContext(NonCancellable) {
            if (!store.clear(profile.id, SignOutReason.UserRequest)) return@withContext
            val tokens = (profile.session.credential as? Credential.RotatingTokens)?.tokens ?: return@withContext
            logout.close(profile.session.serverUrl, tokens.refreshToken)
        }
    }

    /** The session of a profile removed while it slept: closed on its server at best, no profile being open. */
    override suspend fun endCredential(serverUrl: String, credential: Credential) {
        (credential as? Credential.RotatingTokens)?.let { logout.close(serverUrl, it.tokens.refreshToken) }
    }
}
