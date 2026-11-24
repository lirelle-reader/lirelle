package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.awaitLoaded
import app.tfdn.lirelle.source.komga.network.dto.OAuth2ProviderDto
import app.tfdn.lirelle.source.komga.network.dto.UserDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException

/**
 * The roles a download needs: the administrator's consent to downloads, and the pages it fetches. Komga has no
 * role hierarchy: `ADMIN` implies neither.
 */
private val DownloadRoles = setOf("FILE_DOWNLOAD", "PAGE_STREAMING")

/** When a key was made, as its comment says it: to the second, which keeps two keys of one device apart. */
private val CommentTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Signing in to a Komga server, and out of a Komga profile. Komga has no token for an app: whichever way the
 * account signs in, the app makes a key of its own with the session that sign-in opened, ends that session, and
 * the profile holds the key ([KomgaKeys]). By email and password (HTTP Basic), the password kept nowhere; or
 * through the server's identity provider in a WebView: its address begun here, its pages shown by the app's
 * screen, which hands over the session cookie the flow ended with.
 *
 * A key the app lets go of is revoked ([revocation]): the current profile's when it is signed out of, the key of
 * an account added again ([KomgaKeys]), and the key of a profile removed while it slept ([endCredential]).
 *
 * @param now and [zone] say when a key is made, in its comment.
 */
internal class KomgaSignIn(
    private val store: SessionStore,
    private val api: KomgaApis<KomgaPublicApi>,
    private val errors: KomgaErrors,
    deviceLabel: DeviceLabelProvider,
    private val revocation: KomgaRevocation,
    now: () -> Instant = Instant::now,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : SourceSignIn {

    private val keys = KomgaKeys(store, revocation, errors, deviceLabel, now, zone)

    /** An email and a password, and each provider through a WebView: Komga's flow ends in a session cookie. */
    override suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions> = errors.call(KomgaCall.Other) {
        val answer = api.forServer(serverUrl).providers()
        if (!answer.isSuccessful) throw HttpException(answer)
        val providers = answer.body()?.use { KomgaJson.decodeFromString(Providers, it.string()) }.orEmpty()
        LoginOptions(
            passwordEnabled = true,
            oidcProviders = providers.map {
                OidcProvider(it.registrationId, it.name, clientId = "", scopes = "", ProviderFlow.WebView)
            },
            identifier = SignInIdentifier.Email,
        )
    }

    override suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit> =
        keys.signInWith(serverUrl, KomgaSessionProof.byPassword(api.forServer(serverUrl), identifier, password))

    /**
     * The provider's page reached Komga's own, signed in: the session it opened is Komga's session cookie among
     * [cookies]. Without one, the sign-in did not succeed, and nothing is sent.
     */
    override suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit> {
        val session = sessionIn(cookies) ?: return ApiResult.Failure(ApiError.SignInRefused)
        return keys.signInWith(serverUrl, KomgaSessionProof.ofSession(api.forServer(serverUrl), session))
    }

    /** Komga's own address for the provider's flow, under the server's base path; the WebView takes it from there. */
    override suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<String> = ApiResult.Success(
        serverUrl.toHttpUrl().newBuilder()
            .addPathSegments("oauth2/authorization")
            .addPathSegment(provider.slug)
            .build()
            .toString(),
    )

    /** No browser comes back to the app for Komga: its flow ends in the WebView. */
    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> = ApiResult.Failure(ApiError.OidcRefused)

    override suspend fun hasPendingOidc(): Boolean = false

    override suspend fun answersPendingOidc(redirectUri: String): Boolean = false

    override suspend fun cancelOidc() = Unit

    /**
     * Removes the profile [profileId] when it is the current one and a Komga profile, then revokes its key; any
     * other is left as it is, neither removed nor its credential sent anywhere.
     *
     * Locally first: whatever the server answers, or if it does not, the user is signed out. Nothing is sent when
     * something got there first — a second tap, a sign-in that made another profile current.
     *
     * The removal and the revocation to the end together, even when the caller goes away: the profile switch that
     * the removal sets off tears the screen down, and a key let go of must not stay valid.
     */
    override suspend fun signOut(profileId: String) {
        store.awaitLoaded()
        // The profile and its key in one read: the key revoked is the one of the profile removed here.
        val profile = store.current.value?.takeIf { it.id == profileId && it.kind == ServerKind.Komga } ?: return
        withContext(NonCancellable) {
            if (!store.clear(profile.id, SignOutReason.UserRequest)) return@withContext
            val key = profile.session.credential as? Credential.ApiKey ?: return@withContext
            revocation.revoke(profile.session.serverUrl, key)
        }
    }

    /** The key of a profile removed while it slept: revoked with itself, no profile being open. */
    override suspend fun endCredential(serverUrl: String, credential: Credential) {
        (credential as? Credential.ApiKey)?.let { revocation.revoke(serverUrl, it) }
    }

    private companion object {
        val Providers = ListSerializer(OAuth2ProviderDto.serializer())
    }
}

/**
 * What a key made on [deviceName] at [madeAt] is named, as Komga's web interface lists it:
 * "Lirelle — Galaxy Tab S10+ — 2026-11-22 10:15:30". The app, the device, and when, to the second: Komga refuses a
 * comment the account already gave a key, and two sign-ins of one device are never in the same second.
 */
internal fun keyComment(deviceName: String, madeAt: LocalDateTime): String =
    "Lirelle — $deviceName — ${madeAt.format(CommentTime)}"

/** The account as the app keeps it: Komga names its accounts by their email, and gives them no picture. */
internal fun UserDto.toProfile() = UserProfile(
    id = id,
    username = email,
    name = email,
    email = email,
    canDownload = roles.containsAll(DownloadRoles),
    avatarUrl = null,
)
