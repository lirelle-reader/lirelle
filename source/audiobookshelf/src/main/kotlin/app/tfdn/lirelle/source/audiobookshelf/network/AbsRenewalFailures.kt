package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.session.Credential
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** How long after a renewal failed the callers that waited behind it are given its failure instead of asking again. */
private val WaitedWindow: Duration = Duration.ofSeconds(10)

/** How long after the server answered 429 to a renewal nothing is asked again for the same sign-in. */
private val TooManyCoolOff: Duration = Duration.ofSeconds(60)

/**
 * The last renewal of [AbsTokenRefresher] that failed, so that it is not asked again at once: renewals count against
 * the server's login limit (40 per 10 minutes per address, shared by a household), and a sign-in out of it is a
 * whole household's. About one credential — the sign-in and the refresh token sent — and never about another: a
 * sign-in made meanwhile is never held back.
 *
 * Used under the refresher's lock, except [mark], taken before it. A renewal answered 429 fails with
 * [TooManyRenewals].
 */
internal class AbsRenewalFailures(private val now: () -> Instant) {

    private class Failure(
        val signInId: String,
        val refreshToken: String,
        val error: IOException,
        val at: Instant,
        val tooMany: Boolean,
    )

    @Volatile
    private var recorded = 0L
    private var last: Failure? = null

    /** Where the failures stand, taken by a caller before it waits for the lock. */
    fun mark(): Long = recorded

    /**
     * Throws when the renewal of [credential] is not to be asked now: for a minute after the server answered 429 to
     * it; and, to a caller that waited — it took [mark] before the failure — while it failed a few seconds ago, that
     * same failure.
     */
    fun check(credential: Credential.RotatingTokens, mark: Long) {
        val failure = last?.takeIf { it.isAbout(credential) } ?: return
        val since = Duration.between(failure.at, now())
        // A clock set back meanwhile pauses nothing: it would pause for as long as it went back.
        if (failure.tooMany && !since.isNegative && since < TooManyCoolOff) {
            throw IOException("Token renewal paused after too many attempts", failure.error)
        }
        if (recorded > mark && since < WaitedWindow) throw failure.error
    }

    /** What [renewal] of [credential] gives; its failure, kept for the callers after it. */
    suspend fun <T> remembering(credential: Credential.RotatingTokens, renewal: suspend () -> T): T = try {
        renewal()
    } catch (failure: TooManyRenewals) {
        throw record(credential, failure, tooMany = true)
    } catch (failure: IOException) {
        throw record(credential, failure, tooMany = false)
    }

    /** The renewal of [credential] failed with [error], handed back; [tooMany] when the server answered 429. */
    private fun record(credential: Credential.RotatingTokens, error: IOException, tooMany: Boolean): IOException {
        last = Failure(credential.signInId, credential.tokens.refreshToken, error, now(), tooMany)
        recorded++
        return error
    }

    private fun Failure.isAbout(credential: Credential.RotatingTokens) =
        signInId == credential.signInId && refreshToken == credential.tokens.refreshToken
}

/** The server answered 429 to a renewal: past its login limit, it renews nothing for a while. */
internal class TooManyRenewals : IOException("Token renewal refused: too many attempts")
