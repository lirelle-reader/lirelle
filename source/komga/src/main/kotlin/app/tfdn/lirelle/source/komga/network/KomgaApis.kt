package app.tfdn.lirelle.source.komga.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * How Komga's answers are read: a field it adds later is ignored (its answers grow, `notableEntities` came in
 * 1.29.0), and a null it leaves out is no error.
 */
internal val KomgaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * A Retrofit [service] for a Komga server whose address is known at run time, one per address. The routes are
 * relative to the server's address, so that a base path (`https://host/komga`) is kept.
 */
internal class KomgaApis<T : Any>(
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
