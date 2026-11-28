package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcStart
import app.tfdn.lirelle.core.network.PastedKeySignIn
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.awaitLoaded
import app.tfdn.lirelle.source.kavita.network.dto.OidcSettingsDto
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl

/** What Kavita's one identity provider is called by the app: the server names none. */
private const val ProviderSlug = "oidc"

/** What Kavita's own sign-in page calls a provider the server gives no name. */
private const val UnnamedProvider = "OpenID Connect"

/**
 * Signing in to a Kavita server, and out of a Kavita profile, in three ways. By username and password, or through
 * the server's identity provider in a WebView — its address begun here, its pages shown by the app's screen, which
 * hands over the cookies the flow ended with: either way the app makes a key of its own with the session that
 * sign-in opened, and the profile holds the key ([KavitaKeys]); the password and the session's tokens are kept
 * nowhere. Or with a key the user pasted ([KavitaPastedKey]), which stays theirs.
 *
 * A key the app made and lets go of is deleted ([revocation]): the current profile's when it is signed out of,
 * the key of an account added again ([KavitaProfiles]), and the key of a profile removed while it slept
 * ([endCredential]). A key the user pasted is only forgotten.
 *
 * @param now and [zone] say when a key is made, in its name.
 */
internal class KavitaSignIn(
    private val store: SessionStore,
    private val api: KavitaApis<KavitaPublicApi>,
    private val errors: KavitaErrors,
    deviceLabel: DeviceLabelProvider,
    private val revocation: KavitaRevocation,
    now: () -> Instant = Instant::now,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : SourceSignIn,
    PastedKeySignIn by KavitaPastedKey(api, errors, KavitaProfiles(store, revocation)) {

    private val keys = KavitaKeys(KavitaProfiles(store, revocation), errors, deviceLabel, now, zone)

    /**
     * A username and a password unless the server takes none, its identity provider through a WebView when it has
     * one — Kavita's flow ends in a session cookie —, named as the server names it or, without a name, as Kavita's
     * own page does, and a key the user pasted, always.
     */
    override suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions> = errors.call(KavitaCall.Other) {
        val said = api.forServer(serverUrl).oidcSettings().answer().textUpTo(MaxSettingsBytes)
            ?: throw SerializationException("Longer than what Kavita says of its identity provider")
        val settings = KavitaJson.decodeFromString(OidcSettingsDto.serializer(), said)
        val name = settings.providerName.ifBlank { UnnamedProvider }
        val provider = OidcProvider(ProviderSlug, name, clientId = "", scopes = "", ProviderFlow.WebView)
        LoginOptions(
            passwordEnabled = !settings.disablePasswordAuthentication,
            oidcProviders = listOfNotNull(provider.takeIf { settings.enabled }),
            identifier = SignInIdentifier.Username,
            keyEntry = true,
        )
    }

    override suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit> =
        keys.signInWith(serverUrl, KavitaSessionProof.byPassword(api.forServer(serverUrl), identifier, password))

    /**
     * The provider's page reached Kavita's own, signed in: the session it opened is Kavita's session cookie among
     * [cookies]. Without one, the sign-in did not succeed, and nothing is sent.
     */
    override suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit> {
        val session = sessionIn(cookies) ?: return ApiResult.Failure(ApiError.SignInRefused)
        return keys.signInWith(serverUrl, KavitaSessionProof.ofSession(api.forServer(serverUrl), session))
    }

    /**
     * Kavita's own address for its provider's flow, under the server's base path; the WebView takes it from there, and
     * no redirect comes back to the app.
     */
    override suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<OidcStart> = ApiResult.Success(
        OidcStart(
            url = serverUrl.toHttpUrl().newBuilder().addPathSegments(ProviderSignInPage).build().toString(),
            redirectScheme = null,
        ),
    )

    /** No browser comes back to the app for Kavita: its flow ends in the WebView. */
    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> = ApiResult.Failure(ApiError.OidcRefused)

    override suspend fun hasPendingOidc(): Boolean = false

    override suspend fun answersPendingOidc(redirectUri: String): Boolean = false

    override suspend fun cancelOidc() = Unit

    /**
     * Removes the profile [profileId] when it is the current one and a Kavita profile, then deletes its key when
     * the app made it; any other is left as it is, neither removed nor its credential sent anywhere.
     *
     * Locally first: whatever the server answers, or if it does not, the user is signed out. Nothing is sent when
     * something got there first — a second tap, a sign-in that made another profile current.
     *
     * The removal and the deletion to the end together, even when the caller goes away: the profile switch that
     * the removal sets off tears the screen down, and a key let go of must not stay valid.
     */
    override suspend fun signOut(profileId: String) {
        store.awaitLoaded()
        // The profile and its key in one read: the key deleted is the one of the profile removed here.
        val profile = store.current.value?.takeIf { it.id == profileId && it.kind == ServerKind.Kavita } ?: return
        withContext(NonCancellable) {
            if (!store.clear(profile.id, SignOutReason.UserRequest)) return@withContext
            val key = profile.session.credential as? Credential.ApiKey ?: return@withContext
            revocation.revoke(profile.session.serverUrl, key)
        }
    }

    /** The key of a profile removed while it slept: deleted with itself when the app made it, no profile being open. */
    override suspend fun endCredential(serverUrl: String, credential: Credential) {
        (credential as? Credential.ApiKey)?.let { revocation.revoke(serverUrl, it) }
    }
}
