package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the open profile's server says it can do: the `features` of `GET app-info`. A BookOrbit server refuses a
 * parameter it does not know, so what a later version added is sent only to a server that announces it.
 *
 * Asked once and kept for the profile's session: a server upgraded meanwhile is seen at the next start of the
 * app. A read that failed keeps nothing, so the next question asks again; until then the server has no feature.
 */
internal class ServerFeatures(
    private val api: ApiProvider<AccountApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
) {
    private val reading = Mutex()

    @Volatile
    private var announced: Set<String>? = null

    suspend fun has(feature: String): Boolean = feature in (announced ?: read()).orEmpty()

    private suspend fun read(): Set<String>? = reading.withLock {
        announced ?: ask()?.also { announced = it }
    }

    private suspend fun ask(): Set<String>? {
        val url = serverUrl() ?: return null
        val answer = errors.call(CallKind.Authenticated) { api.forServer(url).appInfo().features.toSet() }
        return (answer as? ApiResult.Success)?.value
    }

    companion object {
        /** Since 3.10.0: linked books are editions of one work, and the books of an author can be folded. */
        const val BookEditions = "book-editions"
    }
}
