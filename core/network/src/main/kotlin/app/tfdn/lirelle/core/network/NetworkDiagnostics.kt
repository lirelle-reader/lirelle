package app.tfdn.lirelle.core.network

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Interceptor
import okhttp3.Response

/** How deep the causes of a failure are listed. */
private const val CausesListed = 8

/** How deep the causes of a failure are looked through for a connection that could not be made. */
private const val CauseDepth = 8

/**
 * Whether this failure says the request never left: the connection itself could not be made (no network, an
 * unknown host, a refused connection, no route, a failed TLS handshake, a connection timeout). A timeout or a
 * reset once connected, or a failure while reading the answer, may come after the server acted.
 */
fun Throwable.neverReachedServer(): Boolean = generateSequence(this) { it.cause }.take(CauseDepth).any {
    it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
        it is SSLHandshakeException ||
        (it is SocketTimeoutException && it.message?.contains("connect", ignoreCase = true) == true)
}

/** Where failures of network calls are told, for diagnosis. */
fun interface NetworkLog {
    fun warn(message: String)

    companion object {
        /** Tells nothing: release builds, tests. */
        val None = NetworkLog {}

        /** Logcat, under the tag "Network": for debug builds only. */
        val Logcat = NetworkLog { Log.w("Network", it) }
    }
}

/**
 * Where the network layer tells why a call could not reach the server: nowhere unless a debug build sets
 * [log] at start-up. What is told carries no host beyond what an exception says, no query, no header.
 */
object NetworkDiagnostics {
    @Volatile
    var log: NetworkLog = NetworkLog.None
}

/**
 * "SocketTimeoutException: timeout, causes [SocketException]", without any query an exception's message holds:
 * what a source tells [NetworkDiagnostics.log] of a call that could not reach its server.
 */
fun Throwable.diagnosis(): String {
    val causes = generateSequence(cause) { it.cause }.take(CausesListed).map { it.javaClass.simpleName }.toList()
    val text = message?.substringBefore('?')
    return "${javaClass.simpleName}: $text, causes $causes"
}

/**
 * Tells [log] of each request whose sending failed (its method and path, never its query: search terms
 * are the user's), with the failure, then lets the failure go on as it was.
 */
class FailureLoggingInterceptor(private val log: () -> NetworkLog = { NetworkDiagnostics.log }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (failure: IOException) {
            log().warn("${request.method} ${request.url.encodedPath} failed: ${failure.diagnosis()}")
            throw failure
        }
    }
}
