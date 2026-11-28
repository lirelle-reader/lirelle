package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcStart
import app.tfdn.lirelle.core.network.Pkce
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.PendingOidcStore
import java.time.Duration
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import retrofit2.Response

/** What the app calls Audiobookshelf's one identity provider: the server's own route names it so. */
internal const val ProviderSlug = "openid"

/**
 * How long a sign-in through the provider waits for its return: the time to sign in at the provider, with room to
 * spare — as long as a 2.38 server keeps its state. Past it the return is refused: a browser tab finished much later
 * must not sign anybody in, nor change who is current, long after the user moved on.
 */
private val PendingOidcLifetime: Duration = Duration.ofMinutes(10)

/** What the first call answers when the server allows the return address: a redirect to the provider's page. */
private const val Found = 302

/** What it answers when the server does not allow the return address (`Invalid redirect_uri`). */
private const val BadRequest = 400

/**
 * A sign-in through an Audiobookshelf server's identity provider, in the browser, as BookOrbit's is: begun by the
 * app's own first call, ended by the code the browser brings back, exchanged for the session; saved by [profiles]
 * as a password sign-in is. What waits in between ([pendingOidc]) is the app's state and PKCE verifier, the address
 * the server took for the return, and the cookies of the first call: the exchange is made in the session they name.
 */
internal class AbsOidc(
    private val pendingOidc: PendingOidcStore,
    private val api: AbsApis<AbsOidcApi>,
    private val errors: AbsErrors,
    private val profiles: AbsProfiles,
    private val now: () -> Instant,
) {

    /**
     * Begins a sign-in on [serverUrl], returning to Lirelle's own address when the server allows it and to the
     * official app's otherwise; the provider's page to open in the browser, as the server gave it, with the scheme of
     * the address taken: the browser must watch for that one, the return comes nowhere else.
     */
    suspend fun begin(serverUrl: String): ApiResult<OidcStart> = errors.call(AbsCall.Other) {
        val state = Pkce.randomToken()
        val verifier = Pkce.randomToken()
        val challenge = Pkce.challenge(verifier)
        val begun = firstCall(serverUrl, LirelleReturn, state, challenge)
            ?: firstCall(serverUrl, OfficialAppReturn, state, challenge)
            ?: throw ApiErrorException(ApiError.Unexpected)
        val begunAt = now().toEpochMilli()
        pendingOidc.save(
            PendingOidcRequest(serverUrl, ProviderSlug, state, verifier, "", begunAt, begun.address, begun.cookies),
        )
        OidcStart(begun.providerPage, redirectScheme = begun.address.substringBefore(':'))
    }

    /** The first call returning to [address]; null when the server does not allow that address. */
    private suspend fun firstCall(serverUrl: String, address: String, state: String, challenge: String): Begun? {
        val answer = api.forServer(serverUrl).begin("code", challenge, "S256", address, state)
        return when (answer.code()) {
            Found -> begun(answer, address, state)
            BadRequest -> null
            else -> throw HttpException(answer)
        }
    }

    /**
     * Ends the sign-in that waits with [redirectUri], the browser's return, when it comes to the address the server
     * took with the sign-in's state; any other return is refused, and leaves that sign-in waiting for its own. Its
     * own return ends it whatever comes of it: refused when it brings no code or comes too late, else its code
     * exchanged, once — the server's session for it is used by the first exchange, whatever it answers.
     */
    suspend fun complete(redirectUri: String): ApiResult<Unit> {
        val address = pendingOidc.peek()?.redirectUri ?: return ApiResult.Failure(ApiError.OidcRefused)
        val answer = AbsOidcReturn.parse(redirectUri, address)
        // Taken only when it is the one this answers: anything can send this link.
        val pending = answer.state?.let { state ->
            pendingOidc.takeWhen { it.state == state && it.redirectUri == address }
        }
        if (pending == null || answer !is AbsOidcReturn.Code || pending.expired(now())) {
            return ApiResult.Failure(ApiError.OidcRefused)
        }
        return errors.call(AbsCall.OidcExchange) {
            val login = api.forServer(pending.serverUrl)
                .exchange(pending.state, answer.code, pending.codeVerifier, pending.cookies)
                .answer()
            profiles.save(pending.serverUrl, login.user)
        }
    }

    /**
     * Whether [redirectUri] is the return of the sign-in that waits: to the address the server took for it, with its
     * state. A sign-in of another kind that waits (BookOrbit's) names no such address, and none is its.
     */
    suspend fun answers(redirectUri: String): Boolean {
        val pending = pendingOidc.peek() ?: return false
        val address = pending.redirectUri ?: return false
        return AbsOidcReturn.parse(redirectUri, address).state == pending.state
    }

    suspend fun isPending(): Boolean = pendingOidc.isPending()

    suspend fun cancel() {
        pendingOidc.clear()
    }
}

/**
 * A sign-in the server took: the [providerPage] to open, the return [address] it allowed, and the [cookies] of its
 * session as one `Cookie` header.
 */
private class Begun(val providerPage: String, val address: String, val cookies: String) {
    /** Never prints the cookies: this text may end up in logs and in failed assertions. */
    override fun toString(): String = "Begun(address=$address)"
}

/**
 * What the server's redirect to its provider says. The page must be in `https` — what is typed there could be read
 * otherwise — and carry the state sent, which the provider brings back; the cookies must be there, or the code could
 * not be exchanged: the user would sign in at the provider for nothing.
 */
private fun begun(answer: Response<Unit>, address: String, state: String): Begun {
    val location = answer.headers()["Location"]
    val page = location?.toHttpUrlOrNull()
    // Names and values only: what the server says of them is for a browser to read.
    val cookies = answer.headers().values("Set-Cookie")
        .map { it.substringBefore(';').trim() }
        .filter { it.indexOf('=') > 0 }
    val refusal = when {
        location == null || page == null -> ApiError.Unexpected
        !page.isHttps -> ApiError.UnencryptedSignInPage
        page.queryParameter("state") != state || cookies.isEmpty() -> ApiError.Unexpected
        else -> return Begun(location, address, cookies.joinToString("; "))
    }
    throw ApiErrorException(refusal)
}

/**
 * Whether this sign-in was begun too long before [now] to be completed. One that does not say when it was begun is.
 * So is one begun later than now by more than a sign-in lasts: the clock was set back, and nothing tells how old it
 * is.
 */
private fun PendingOidcRequest.expired(now: Instant): Boolean {
    val begunAt = begunAtMillis ?: return true
    return Duration.between(Instant.ofEpochMilli(begunAt), now).abs() > PendingOidcLifetime
}
