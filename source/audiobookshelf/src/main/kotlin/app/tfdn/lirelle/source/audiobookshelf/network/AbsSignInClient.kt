package app.tfdn.lirelle.source.audiobookshelf.network

import java.net.ProtocolException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Every header that proves an Audiobookshelf account: an access token, the cookies of a provider's sign-in, a
 * refresh token.
 */
private val CredentialHeaders = listOf("Authorization", "Cookie", RefreshTokenHeader)

/**
 * The client of Audiobookshelf's sign-in and of the end of its sessions, made from the public one: what proves the
 * account — the password in the body of the sign-in as much as a header — goes to the server it was given for only,
 * never along a redirect to another server.
 */
internal fun absSignInClient(publicClient: OkHttpClient): OkHttpClient =
    publicClient.newBuilder().addNetworkInterceptor(CredentialGuard).build()

/**
 * Keeps what proves an account from a request that a redirect sends to another server than the call's — another
 * host, another port, or from `https` to cleartext. A network interceptor: it sees each request the redirects lead
 * to, before anything of it is sent.
 *
 * A request with a body is not sent at all, and its call fails ([sendsBodyAwayFrom]): the password is in the body
 * of the sign-in, which OkHttp sends again along a 307 or a 308, to any host and down to cleartext. The failure is
 * a [ProtocolException], which OkHttp does not try again on another route, and which the errors tell as any call
 * that could not be made: a server out of reach.
 *
 * A request without a body goes on bare of every credential. OkHttp takes `Authorization` off between hosts only;
 * a cookie or a refresh token would otherwise follow the redirect.
 */
private object CredentialGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = chain.call().request().url
        if (request.url.isSameServer(server)) return chain.proceed(request)
        // Neither the address nor anything of the request: this message may be logged.
        if (request.sendsBodyAwayFrom(server)) throw ProtocolException("A redirect to another server: nothing is sent")
        val bare = request.newBuilder()
        CredentialHeaders.forEach(bare::removeHeader)
        return chain.proceed(bare.build())
    }
}

/**
 * Whether this request would carry a body to another server than [server], the one its call was made for: another
 * host, another port, or another scheme — `https` left for cleartext on the very same host.
 */
internal fun Request.sendsBodyAwayFrom(server: HttpUrl): Boolean = body != null && !url.isSameServer(server)

private fun HttpUrl.isSameServer(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
