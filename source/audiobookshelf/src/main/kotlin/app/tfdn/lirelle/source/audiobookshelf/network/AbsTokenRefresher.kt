package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.isOnServer
import app.tfdn.lirelle.core.network.neverReachedServer
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsRefreshDto
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsRenewedDto
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response

/** How long one attempt of a renewal may take, from its connection to its last byte. */
private const val RefreshCallTimeoutSeconds = 8L

/**
 * The renewal's API as the app makes it, from the public client ([publicClient]): through the sign-in's client
 * ([absSignInClient]), so that the refresh token goes along no redirect to another server; not retried by OkHttp
 * (`retryOnConnectionFailure`), since [AbsTokenRefresher] decides alone when a token is sent again; and each attempt
 * short, so that the second one starts while the server still answers a replayed token.
 */
internal fun absRefreshApi(publicClient: OkHttpClient): AbsApis<AbsPublicApi> = AbsApis(
    absSignInClient(publicClient).newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(RefreshCallTimeoutSeconds, TimeUnit.SECONDS)
        .build(),
    AbsJson,
    AbsPublicApi::class.java,
)

/** How long before it expires an access token is renewed: a request sent with it must still find it good. */
private const val ExpiryMarginSeconds = 60L

private const val Unauthorized = 401
private const val TooManyRequests = 429
private val ServerErrors = 500..599
private const val Authorization = "Authorization"
private const val BearerPrefix = "Bearer "

/**
 * Audiobookshelf's authorizer for the authenticated client of `core/network`: the access token as a bearer header,
 * to the profile's server only, and the renewal of the session, before a request when the token expires within a
 * minute, and after a 401 to the token sent. The only place that calls `auth/refresh`.
 *
 * The server rotates the refresh token at every renewal, and refuses the one replaced — at once below 2.35, after
 * a grace of ten minutes from it. So renewals are serialised behind one mutex, a caller that arrives after another
 * renewed gets the new tokens without a second request, and the new pair is stored before anything uses it, to the
 * end even when the caller goes away: a pair lost signs the user out.
 *
 * A renewal that may have reached the server without its answer coming back (no answer, a reset, a server error,
 * an unreadable answer) may have rotated the token: it is tried again once, at once, with the same token — from
 * 2.35 the server answers that replay within its grace; below, it refuses it and the profile is marked, as the lost
 * answer would have left it anyway. One whose connection could not even be made rotated nothing and is not tried
 * again. Nothing more: renewals count against the server's login limit (40 per 10 minutes per address, shared by a
 * household), and past it the server answers 429, which marks nothing. The call itself is not retried by OkHttp
 * and times out quickly ([absRefreshApi]).
 *
 * Only a 401 says the session is over, and it removes nothing: the profile is marked as needing a sign-in
 * ([ProfileSession.expire]), and until the same account signs in again every request for the server fails here, at
 * once and without a word to the server, as it would without a network.
 *
 * One per profile, over [session], that profile's own: a renewal that ends after its profile closed stores its
 * tokens for that profile or nowhere. None starts once the profile is gone ([ProfileSession.open]): the sign-out is
 * sending that refresh token to the server to end the session. A sign-in made while a renewal was on its way wins:
 * the answer about the tokens of before changes nothing, and the caller is given the new sign-in's.
 */
internal class AbsTokenRefresher(
    private val session: ProfileSession,
    private val api: AbsApis<AbsPublicApi>,
    private val now: () -> Instant,
) : SessionAuthorizer {
    private val mutex = Mutex()

    /** The access token as a bearer header, renewed first when it is about to expire. */
    override suspend fun header(target: HttpUrl): AuthHeader? = freshTokens(target = target)?.bearer()

    /** The access token refused is renewed, and the request replayed with the new one. */
    override suspend fun rejected(sent: String, target: HttpUrl): AuthHeader? =
        freshTokens(rejectedAccessToken = sent.removePrefix(BearerPrefix), target = target)?.bearer()

    private fun Tokens.bearer() = AuthHeader(Authorization, BearerPrefix + accessToken)

    /**
     * Tokens that can be used right now, renewed first when needed.
     *
     * @param rejectedAccessToken an access token the server just answered 401 to, if any.
     * @param target the address the tokens will be sent to, if known: they are only handed out, and only renewed,
     *   for the session's own server.
     * @return null when [target] is not on the session's server, and only then.
     * @throws IOException when a renewal could not be made or failed without the server refusing it, when one is
     *   needed for a profile that is gone, when the server has just refused it, and for as long as the profile then
     *   waits for a sign-in.
     */
    suspend fun freshTokens(rejectedAccessToken: String? = null, target: HttpUrl? = null): Tokens? = mutex.withLock {
        // Read before the session: the mark falls only once the session carries the tokens of the new sign-in.
        val signInNeeded = session.signInNeeded.value
        val known = session.session.value
        if (target != null && !target.isOnServer(known.serverUrl)) return@withLock null
        // Under the lock: whoever waited here behind the renewal that was refused learns of it.
        if (signInNeeded) throw IOException("The session needs a sign-in: nothing is asked in its name")
        val current = known.credential as? Credential.RotatingTokens
            ?: throw IOException("The session holds no Audiobookshelf tokens: nothing is asked in its name")
        val expiresSoon = current.tokens.accessTokenExpiresAt.isBefore(now().plusSeconds(ExpiryMarginSeconds))
        if (!expiresSoon && current.tokens.accessToken != rejectedAccessToken) return@withLock current.tokens
        // Asked under the lock: a renewal that waited here for another one learns of a sign-out made meanwhile.
        if (!session.open) throw IOException("The profile is closed: its session is not renewed")

        // Once the request is sent the server may have rotated the refresh token: a caller that is cancelled must
        // not stop the answer from being stored.
        withContext(NonCancellable) { renew(known.serverUrl, current) }
            // The same failure as every request after this one: nothing goes out without tokens.
            ?: throw IOException("The server refused to renew the session: nothing is asked in its name")
    }

    /**
     * One attempt, and a second with the same token when the first may have reached the server without its answer
     * coming back — unless the profile went meanwhile: nothing would keep what came back.
     *
     * @return null when the server refused to renew [current] and nothing has replaced them.
     */
    private suspend fun renew(serverUrl: String, current: Credential.RotatingTokens): Tokens? = try {
        attempt(serverUrl, current)
    } catch (failure: MayHaveRotated) {
        if (!session.open) throw failure
        attempt(serverUrl, current)
    }

    /**
     * One call to `auth/refresh` with [current]. Its answer is about [current] and nothing else, and the session is
     * told so: it keeps the tokens, or the mark, only if those are still its own.
     *
     * @throws MayHaveRotated when the server may have rotated the token without the answer telling the new one.
     * @throws IOException when it did not: the connection was never made, or the server answered without renewing.
     */
    private suspend fun attempt(serverUrl: String, current: Credential.RotatingTokens): Tokens? {
        val answer = send(serverUrl, current.tokens.refreshToken)
        // What a refusal says in its body is never read.
        answer.errorBody()?.close()
        if (!answer.isSuccessful) return refused(answer.code(), current)
        val renewed = answer.body()?.user?.toTokens(previous = current.tokens)
            ?: throw MayHaveRotated("Token renewal returned no answer to read")
        session.updateCredential(current.copy(tokens = renewed), replacing = current)
        return signedInSince(current) ?: renewed
    }

    /**
     * The call to `auth/refresh` with [refreshToken], and its answer, whatever its status.
     *
     * @throws MayHaveRotated when no answer came, or one that cannot be read, after the server may have had it.
     * @throws IOException when the connection was never made: nothing was sent.
     */
    private suspend fun send(serverUrl: String, refreshToken: String): Response<AbsRefreshDto> = try {
        api.forServer(serverUrl).refresh(refreshToken)
    } catch (exception: SerializationException) {
        throw MayHaveRotated("Token renewal returned an unreadable answer", exception)
    } catch (exception: IOException) {
        throw if (exception.neverReachedServer()) exception else MayHaveRotated("No answer to the renewal", exception)
    }

    /** What the server's refusal, with the status [code], of the renewal of [current] means. */
    private suspend fun refused(code: Int, current: Credential.RotatingTokens): Tokens? = when (code) {
        Unauthorized -> {
            session.expire(refused = current)
            signedInSince(current)
        }
        in ServerErrors -> throw MayHaveRotated("Token renewal failed with HTTP $code")
        TooManyRequests -> throw IOException("Token renewal refused: too many attempts")
        else -> throw IOException("Token renewal failed with HTTP $code")
    }

    /**
     * The tokens of a sign-in made while the renewal of [asked] was on its way, when there was one: the answer about
     * [asked] — renewed or refused — is about a session the profile no longer has. Null when the session is still
     * the one [asked] belonged to, renewed or not, and when it waits for a sign-in.
     */
    private fun signedInSince(asked: Credential.RotatingTokens): Tokens? {
        // The mark first, as in [freshTokens]: it is never down beside tokens the server refused.
        val signInNeeded = session.signInNeeded.value
        val held = session.session.value.credential as? Credential.RotatingTokens
        return held?.takeIf { !signInNeeded && !it.isSameSignIn(asked) }?.tokens
    }

    /**
     * The new pair, its expiries read from it ([AbsJwt]); an answer without the refresh token keeps the one sent. An
     * answer without the access token is unreadable: the server may have rotated the refresh token all the same.
     */
    private fun AbsRenewedDto.toTokens(previous: Tokens): Tokens {
        val access = accessToken?.takeIf { it.isNotBlank() }
            ?: throw MayHaveRotated("Token renewal returned no access token")
        val at = now()
        val refresh = refreshToken?.takeIf { it.isNotBlank() }
        return Tokens(
            accessToken = access,
            accessTokenExpiresAt = AbsJwt.accessExpiry(access, at),
            refreshToken = refresh ?: previous.refreshToken,
            refreshTokenExpiresAt = refresh?.let { AbsJwt.refreshExpiry(it, at) } ?: previous.refreshTokenExpiresAt,
        )
    }
}

/** A renewal that may have reached the server, and rotated the refresh token, without its answer giving the new one. */
private class MayHaveRotated(message: String, cause: Throwable? = null) : IOException(message, cause)
