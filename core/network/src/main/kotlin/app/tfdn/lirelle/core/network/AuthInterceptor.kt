package app.tfdn.lirelle.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val Unauthorized = 401

/**
 * The header a request was given ([name]), and the server it was given for: kept on the request as a tag, which
 * its redirects and its replay carry along, so that the header is only ever sent to that server and its value
 * read back from what was sent.
 */
private class SentHeader(val name: String, val server: HttpUrl)

private fun Request.carrying(header: AuthHeader): Request = newBuilder()
    .header(header.name, header.value)
    .tag(SentHeader::class.java, SentHeader(header.name, url))
    .build()

/** The header this request carries for the server it was given for; null when it carries none, or goes elsewhere. */
private fun Request.sentValue(): String? {
    val sent = tag(SentHeader::class.java)?.takeIf { url.isSameServer(it.server) } ?: return null
    return header(sent.name)
}

/**
 * The address a request was asked with ([asked]) and the one the authorizer sent it to instead ([sent]), which
 * holds the credential: kept on the request as a tag, and only when the two differ, so that a replay is addressed
 * again from [asked], with the credential of then, and a redirect never takes [sent] to another server.
 */
private class SentAddress(val asked: HttpUrl, val sent: HttpUrl)

/** This request sent to the address [authorizer] gives for [target], tagged when that address is not [target]. */
private fun Request.addressedFor(target: HttpUrl, authorizer: SessionAuthorizer): Request {
    val url = authorizer.address(target)
    return newBuilder()
        .url(url)
        .tag(SentAddress::class.java, SentAddress(asked = target, sent = url).takeIf { url != target })
        .build()
}

/**
 * The address this request was asked with: the one the authorizer was given, when the request still goes where
 * the authorizer sent it; its own once a redirect has taken it elsewhere.
 */
private fun Request.askedUrl(): HttpUrl = tag(SentAddress::class.java)?.takeIf { it.sent == url }?.asked ?: url

/**
 * Waits for the authorizer on an OkHttp thread. OkHttp rethrows anything but an [IOException] on that thread,
 * which ends the app, so every other failure leaves here as one. None of them touches the session: only the
 * server's refusal of the credential does — a renewal refused, a key refused — and it marks the profile as
 * needing a sign-in without removing anything. That refusal leaves here as an [IOException] too, for the request
 * that met it and for every one after it until somebody signs in: nothing is sent without the credential.
 */
@Suppress("TooGenericExceptionCaught")
private fun SessionAuthorizer.blocking(ask: suspend SessionAuthorizer.() -> AuthHeader?): AuthHeader? = try {
    runBlocking { ask() }
} catch (exception: IOException) {
    throw exception
} catch (exception: InterruptedException) {
    // The thread was interrupted while waiting: keep the flag for whoever owns the thread.
    Thread.currentThread().interrupt()
    throw InterruptedIOException("Interrupted while waiting for the credential").apply { initCause(exception) }
} catch (exception: Exception) {
    throw IOException("Could not get the credential: ${exception.javaClass.simpleName}", exception)
}

/**
 * Adds the header the profile's source gives to requests for the profile's server, renewed first when it is about
 * to expire, and sends each request to the address the source gives for it ([SessionAuthorizer.address]): its own,
 * or one with the credential in it on the routes that take it there. That address is made here, below the
 * interceptors before this one, so that what the app asks for, keeps and logs never holds the credential. The
 * response's `request` does hold the address the request was sent to: only its path may be logged. Requests for
 * any other host go out untouched. OkHttp calls interceptors on its own threads, so blocking here is expected.
 */
class AuthInterceptor(private val authorizer: SessionAuthorizer) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val asked = chain.request()
        val header = authorizer.blocking { header(asked.url) }
        val addressed = asked.addressedFor(asked.url, authorizer)
        val request = header?.let { addressed.carrying(it) } ?: addressed
        return chain.proceed(request)
    }
}

/**
 * On 401 from the profile's server to the header it was sent, tells the profile's source, which renews the
 * credential — the request is then replayed once with it, at the address the source gives for it then, so that a
 * credential in the address is renewed too — or hands the 401 back, or fails the request: when the server refused
 * the credential, the request fails as it would without a network, the 401 is not handed back, and whoever asked
 * tries again once somebody has signed in.
 */
class TokenAuthenticator(private val authorizer: SessionAuthorizer) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // Already replayed after a 401: give up. A redirect also leaves a prior response, and
        // must not count as a replay.
        if (generateSequence(response.priorResponse) { it.priorResponse }.any { it.code == Unauthorized }) return null
        val request = response.request
        val rejected = request.sentValue() ?: return null
        val target = request.askedUrl()
        val header = authorizer.blocking { rejected(rejected, target) } ?: return null
        return request.addressedFor(target, authorizer).carrying(header)
    }
}

/**
 * Takes the profile's header off a request that a redirect sends to another server. OkHttp does so itself for
 * `Authorization` only; a source's own header (a key) would otherwise follow the redirect.
 *
 * A request whose address holds the credential is not sent to another server at all: it fails instead, once the
 * connection is made and before any of the request is written. A redirect's address may repeat the whole of the
 * one it answers (a proxy's `?rd=…`), and the routes that take the credential in their address (images) never
 * legitimately move to another host. The failure is a [ProtocolException], which OkHttp does not retry.
 */
private class SentCredentialGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val address = request.tag(SentAddress::class.java)
        if (address != null && !request.url.isSameServer(address.sent)) {
            throw ProtocolException("Not redirected to another server: the address holds the credential")
        }
        val sent = request.tag(SentHeader::class.java)
        val kept = if (sent == null || request.url.isSameServer(sent.server)) {
            request
        } else {
            request.newBuilder().removeHeader(sent.name).build()
        }
        return chain.proceed(kept)
    }
}

/**
 * Builds the client that proves the profile's account as its source says, from the client used for public
 * calls. The only place that wires [AuthInterceptor] and [TokenAuthenticator].
 *
 * It gets its own dispatcher: its threads block while a renewal runs on [publicClient], and
 * with a shared one a few waiting calls would use up every slot the renewal needs to start.
 */
fun newAuthenticatedClient(publicClient: OkHttpClient, authorizer: SessionAuthorizer): OkHttpClient =
    publicClient.newBuilder()
        .dispatcher(Dispatcher())
        // First: it sees a failure whatever step it came from.
        .addInterceptor(FailureLoggingInterceptor())
        .addInterceptor(AuthInterceptor(authorizer))
        .addNetworkInterceptor(SentCredentialGuard())
        .authenticator(TokenAuthenticator(authorizer))
        .build()
