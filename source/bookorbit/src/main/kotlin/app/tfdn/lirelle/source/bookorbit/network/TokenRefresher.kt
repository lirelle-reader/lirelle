package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.isOnServer
import app.tfdn.lirelle.core.network.neverReachedServer
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.bookorbit.network.dto.RefreshDto
import app.tfdn.lirelle.source.bookorbit.network.dto.RefreshRequest
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import retrofit2.HttpException

/**
 * How a renewal whose answer did not come is tried again with the same refresh token: after each of
 * [pausesMs], as long as the attempt starts within [withinMs] of the first one, inside the 30 seconds in
 * which the server answers a replayed token with the next one of its chain.
 *
 * @param elapsed milliseconds on a clock that only goes forward.
 */
internal class RefreshRetry(
    val pausesMs: List<Long> = DefaultPausesMs,
    val withinMs: Long = DefaultWithinMs,
    val elapsed: () -> Long = { System.nanoTime() / NanosPerMilli },
    val pause: suspend (Long) -> Unit = { delay(it) },
)

private const val NanosPerMilli = 1_000_000L
private val DefaultPausesMs = listOf(1_000L, 3_000L, 6_000L)

/** Well within the 30 s in which the server answers a replayed refresh token with the next one. */
private const val DefaultWithinMs = 25_000L

/**
 * BookOrbit's authorizer for the authenticated client of `core/network`: the access token as a bearer header, and
 * the renewal of a profile's session, before a request and after a 401 to its token. The only place that calls
 * `auth/refresh`.
 *
 * The server rotates the refresh token on every use and revokes the whole session when an
 * old one is replayed more than 30 seconds later. So renewals are serialised behind one
 * mutex, and a caller that arrives after another one renewed gets the new tokens without a
 * second request.
 *
 * A renewal that may have reached the server without its answer coming back (no answer, a reset, a
 * server error, an unreadable answer) may have rotated the token: it is tried again at once with the same
 * token, as [retry] says, while the server still answers that replay with the next token. One whose
 * connection could not even be made rotated nothing and is not tried again. Only a 401 says the session is
 * over, and it removes nothing: the profile is marked as needing a sign-in.
 * The call itself should not be retried by OkHttp (`retryOnConnectionFailure`) and should time out
 * quickly, so that every attempt stays within the server's grace.
 *
 * A session the server refused to renew is not renewed again, nor used: its profile is kept, marked
 * ([ProfileSession.signInNeeded]), and until the same account signs in again every request for the session's
 * server fails here, at once and without a word to the server, as it would without a network. The request
 * that met the refusal fails in the same way: it does not go out without tokens, and whoever made it — a
 * download, a write that waits — sees a server out of reach and tries again later, not a refusal that would
 * end it for good.
 *
 * A renewal holds its refresh token for up to [RefreshRetry.withinMs], and the same account may sign in again
 * meanwhile. The answer that comes then is about the tokens of before: the session is told which tokens were
 * renewed or refused ([ProfileSession.updateCredential], [ProfileSession.expire]) and changes nothing when they
 * are no longer its own; the caller is given the tokens of the new sign-in.
 *
 * One per profile, over [session], that profile's own: a renewal that ends after its profile closed stores
 * its tokens for that profile or nowhere, never for whoever signed in meanwhile. And none starts once the
 * profile is gone ([ProfileSession.open]): what still asks then — a screen that is leaving, a service's last
 * write — is served the tokens already known while they last, and fails after that. A renewal started then
 * would replay the refresh token that the sign-out is sending to the server to end the session.
 */
internal class TokenRefresher(
    private val session: ProfileSession,
    private val publicApi: ApiProvider<PublicApi>,
    private val retry: RefreshRetry = RefreshRetry(),
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
     * Returns tokens that can be used right now, renewing them first when needed.
     *
     * @param rejectedAccessToken an access token the server just answered 401 to, if any.
     * @param target the URL the tokens will be sent to, if known. Tokens are only handed out,
     * and only renewed, for the session's own server.
     * @return null when [target] is not on the session's server, and only then.
     * @throws IOException when renewal could not be attempted or failed without the server refusing it, when
     * one is needed for a profile that is gone, when the server has just refused to renew the session, and for
     * as long as the session then waits for a sign-in.
     */
    suspend fun freshTokens(rejectedAccessToken: String? = null, target: HttpUrl? = null): Tokens? = mutex.withLock {
        // Read before the session: the mark falls only once the session carries the tokens of the new sign-in.
        val signInNeeded = session.signInNeeded.value
        val known = session.session.value
        if (target != null && !target.isOnServer(known.serverUrl)) return@withLock null
        // Under the lock: whoever waited here behind the renewal that was refused learns of it.
        if (signInNeeded) throw IOException("The session needs a sign-in: nothing is asked in its name")
        val current = known.credential as? Credential.SessionTokens
            ?: throw IOException("The session holds no BookOrbit tokens: nothing is asked in its name")
        val expiresSoon = current.tokens.accessTokenExpiresAt.isBefore(now().plusSeconds(ExpiryMarginSeconds))
        if (!expiresSoon && current.tokens.accessToken != rejectedAccessToken) return@withLock current.tokens
        // Asked under the lock: a renewal that waited here for another one learns of a sign-out made meanwhile.
        if (!session.open) throw IOException("The profile is closed: its session is not renewed")

        // Once the request is sent the server may have rotated the refresh token, so a caller
        // that is cancelled must not stop the answer from being stored.
        withContext(NonCancellable) { renew(known.serverUrl, current) }
            // The same failure as every request after this one: nothing goes out without tokens.
            ?: throw IOException("The server refused to renew the session: nothing is asked in its name")
    }

    /**
     * [attempt], tried again with the same token while the server may have rotated it and the replay is safe.
     *
     * @return null when the server refused to renew [current] and nothing has replaced them.
     */
    private suspend fun renew(serverUrl: String, current: Credential.SessionTokens): Tokens? {
        val started = retry.elapsed()
        var retries = 0
        while (true) {
            val failure = try {
                return attempt(serverUrl, current)
            } catch (failure: IOException) {
                failure
            }
            // The next try, if it can still start within the server's grace.
            val pause = retry.pausesMs.getOrNull(retries++)?.takeIf { retry.elapsed() - started + it <= retry.withinMs }
            // Nothing was sent, so nothing rotated: the caller tries later, as before. And for a profile that
            // went meanwhile the token is not sent again: nothing would keep what came back.
            if (pause == null || failure.neverReachedServer() || !session.open) throw failure
            retry.pause(pause)
        }
    }

    /**
     * One call to `auth/refresh` with [current]. Its answer is about [current] and nothing else, and the
     * session is told so: it keeps the tokens, or the mark, only if those are still its own.
     */
    private suspend fun attempt(serverUrl: String, current: Credential.SessionTokens): Tokens? = try {
        val renewed = publicApi.forServer(serverUrl)
            .refresh(RefreshRequest(current.tokens.refreshToken))
            .toTokens(previous = current.tokens)
        session.updateCredential(current.copy(tokens = renewed), replacing = current)
        signedInSince(current) ?: renewed
    } catch (exception: HttpException) {
        if (exception.code() != Unauthorized) {
            throw IOException("Token renewal failed with HTTP ${exception.code()}", exception)
        }
        session.expire(refused = current)
        signedInSince(current)
    } catch (exception: SerializationException) {
        throw IOException("Token renewal returned an unreadable answer", exception)
    } catch (exception: DateTimeParseException) {
        throw IOException("Token renewal returned an unreadable expiry", exception)
    }

    /**
     * The tokens of a sign-in made while the renewal of [asked] was on its way, when there was one: the same
     * account signed in again, and the answer about [asked] — renewed or refused — is about a session the
     * profile no longer has. The caller is served the session it has now. Null when the session is still the
     * one [asked] belonged to, renewed or not, and when it waits for a sign-in.
     */
    private fun signedInSince(asked: Credential.SessionTokens): Tokens? {
        // The mark first, as in [freshTokens]: it is never down beside tokens the server refused.
        val signInNeeded = session.signInNeeded.value
        val held = session.session.value.credential as? Credential.SessionTokens
        return held?.takeIf { !signInNeeded && !it.isSameSignIn(asked) }?.tokens
    }

    private fun RefreshDto.toTokens(previous: Tokens) = Tokens(
        accessToken = accessToken,
        accessTokenExpiresAt = Instant.parse(accessTokenExpiresAt),
        refreshToken = refreshToken ?: previous.refreshToken,
        refreshTokenExpiresAt = refreshTokenExpiresAt?.let(Instant::parse) ?: previous.refreshTokenExpiresAt,
    )

    private companion object {
        const val ExpiryMarginSeconds = 60L
        const val Unauthorized = 401
        const val Authorization = "Authorization"
        const val BearerPrefix = "Bearer "
    }
}
