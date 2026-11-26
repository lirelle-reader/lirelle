package app.tfdn.lirelle.source.kavita.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Every header that proves a Kavita account: a session's token, a provider's session in its cookie, a key. */
private val CredentialHeaders = listOf("Authorization", "Cookie", ApiKeyHeader)

/**
 * The client of Kavita's sign-in and of the deletion of its keys, made from the public one: what proves the
 * account goes to the server it was given for only, never along a redirect to another server.
 */
internal fun kavitaSignInClient(publicClient: OkHttpClient): OkHttpClient =
    publicClient.newBuilder().addNetworkInterceptor(CredentialGuard).build()

/**
 * Takes every credential off a request that a redirect sends to another server than the call's — another host,
 * another port, or from `https` to cleartext. OkHttp does so itself for `Authorization` between hosts only; a
 * session in a cookie or a key in a header of the server's own would otherwise follow the redirect. A network
 * interceptor: it sees each request the redirects lead to.
 */
private object CredentialGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.isSameServer(chain.call().request().url)) return chain.proceed(request)
        val bare = request.newBuilder()
        CredentialHeaders.forEach(bare::removeHeader)
        return chain.proceed(bare.build())
    }
}

private fun HttpUrl.isSameServer(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
