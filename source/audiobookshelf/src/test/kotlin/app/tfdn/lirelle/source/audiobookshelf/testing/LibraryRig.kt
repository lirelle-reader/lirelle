package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.source.audiobookshelf.library.AbsLibraryApi
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProfileServer
import app.tfdn.lirelle.source.audiobookshelf.network.AbsApis
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson
import app.tfdn.lirelle.source.audiobookshelf.network.AbsTokenRefresher
import app.tfdn.lirelle.source.audiobookshelf.network.absRefreshApi
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * The library's way to the profile's server on [server], as the profile's client makes it as far as the library is
 * concerned: what the authorizer gives goes with each request.
 */
internal fun libraryServer(
    server: MockWebServer,
    session: FakeProfileSession = FakeProfileSession(absSession(server.serverUrl)),
): AbsProfileServer {
    val authorizer = AbsTokenRefresher(session, absRefreshApi(OkHttpClient())) { SignedInAt }
    val bearing = Interceptor { chain ->
        val header: AuthHeader? = runBlocking { authorizer.header(chain.request().url) }
        val request = chain.request().newBuilder()
        header?.let { request.header(it.name, it.value) }
        chain.proceed(request.build())
    }
    val client = OkHttpClient.Builder().addInterceptor(bearing).build()
    return AbsProfileServer(session, AbsApis(client, AbsJson, AbsLibraryApi::class.java), AbsErrors())
}
