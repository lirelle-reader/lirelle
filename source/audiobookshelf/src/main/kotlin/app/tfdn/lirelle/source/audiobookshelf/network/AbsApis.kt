package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsLoginDto
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsLoginRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

/** How Audiobookshelf's answers are read: a field it adds later, and the many the app does not read, are ignored. */
internal val AbsJson: Json = Json { ignoreUnknownKeys = true }

/**
 * What is asked of an Audiobookshelf server without a profile: what tells it from another server and how it signs
 * in, a sign-in, and the end of a session. Answers come with their status ([Response]), which says as much as their
 * body.
 */
internal interface AbsPublicApi {

    /**
     * Public: `{app, serverVersion, authMethods, authFormData…}`. Not read by Retrofit: whoever asks reads it within
     * a bound ([textUpTo]), and closes it.
     */
    @Streaming
    @GET(StatusRoute)
    suspend fun status(): Response<ResponseBody>

    /**
     * Opens a session with a password, the tokens in the answer. The password is in the body: the sign-in's client
     * sends it to no other server ([absSignInClient]).
     */
    @Headers("$ReturnTokensHeader: true")
    @POST(LoginRoute)
    suspend fun login(@Body request: AbsLoginRequest): Response<AbsLoginDto>

    /** Closes the session of [refreshToken] on the server. */
    @POST(LogoutRoute)
    suspend fun logout(@Header(RefreshTokenHeader) refreshToken: String): Response<Unit>
}

/**
 * A Retrofit [service] for an Audiobookshelf server whose address is known at run time, one per address. The routes
 * are relative to the server's address, so that a base path (`https://host/audiobookshelf`) is kept.
 */
internal class AbsApis<T : Any>(
    private val client: OkHttpClient,
    private val json: Json,
    private val service: Class<T>,
) {
    private val cache = ConcurrentHashMap<String, T>()

    /** [serverUrl] is a normalised address without trailing slash. */
    fun forServer(serverUrl: String): T = cache.getOrPut(serverUrl) {
        Retrofit.Builder()
            .baseUrl("$serverUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(service)
    }
}

/**
 * What Audiobookshelf's sign-in asks of its servers, through the sign-in's client made from the public one
 * ([publicClient]): the agent of the app on every request, and nothing that proves an account along a redirect to
 * another server.
 */
internal fun absPublicApi(publicClient: OkHttpClient): AbsApis<AbsPublicApi> =
    AbsApis(absSignInClient(publicClient), AbsJson, AbsPublicApi::class.java)

/**
 * The text of this body when it is no longer than [most] bytes; null when it is longer, or when there is none. No
 * more than that is read, and the body is closed whatever it held. On a route marked `@Streaming` the bytes come
 * from the network as they are asked for, so they are read where waiting is allowed.
 */
internal suspend fun ResponseBody?.textUpTo(most: Long): String? = withContext(Dispatchers.IO) {
    this@textUpTo?.use { body ->
        val source = body.source()
        if (source.request(most + 1)) null else source.readUtf8()
    }
}

/**
 * The body of a successful answer; the answer as an exception otherwise, which carries its status only: what a
 * refusal says in its body is never read into an error.
 */
internal fun <T> Response<T>.answer(): T {
    if (!isSuccessful) throw HttpException(this)
    return body() ?: throw HttpException(this)
}
