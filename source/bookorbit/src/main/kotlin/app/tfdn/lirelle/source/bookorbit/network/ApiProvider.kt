package app.tfdn.lirelle.source.bookorbit.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds a Retrofit [service] for a given server. The server address is chosen at runtime,
 * so the instance cannot be created once at startup. Instances are cached per address.
 */
internal class ApiProvider<T : Any>(
    private val client: OkHttpClient,
    private val json: Json,
    private val service: Class<T>,
) {
    private val cache = ConcurrentHashMap<String, T>()

    /** [serverUrl] is a normalised address without trailing slash. */
    fun forServer(serverUrl: String): T = cache.getOrPut(serverUrl) {
        Retrofit.Builder()
            .baseUrl("$serverUrl/api/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(service)
    }
}
