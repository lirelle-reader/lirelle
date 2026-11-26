package app.tfdn.lirelle.source.kavita.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * How Kavita's answers are read, and its requests written: a field it adds later is ignored, and a null the app
 * sends is written as Kavita's own page writes it (a key that never expires).
 */
internal val KavitaJson: Json = Json { ignoreUnknownKeys = true }

/**
 * A Retrofit [service] for a Kavita server whose address is known at run time, one per address. The routes are
 * relative to the server's address, so that a base path (`https://host/kavita`) is kept.
 */
internal class KavitaApis<T : Any>(
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
 * The body of a successful answer; the answer as an exception otherwise, which carries its status only: what a
 * refusal of Kavita's says in its body may be a stack trace, and is never read into an error.
 */
internal fun <T> Response<T>.answer(): T {
    if (!isSuccessful) throw HttpException(this)
    return body() ?: throw HttpException(this)
}
