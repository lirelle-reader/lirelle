package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.PastedKeySignIn
import app.tfdn.lirelle.core.session.Credential
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What names a pasted key when the server does not say which of the account's keys it is. */
internal const val UnnamedKeyId = "pasted"

/**
 * Signing in to Kavita with a key the user made in its web interface and pasted: the account is read with it, and
 * the profile keeps it as it is. The key stays the user's ([Credential.ApiKey.ownedByApp] is false): the app
 * forgets it when the profile goes, and never deletes it on the server.
 */
internal class KavitaPastedKey(
    private val api: KavitaApis<KavitaPublicApi>,
    private val errors: KavitaErrors,
    private val profiles: KavitaProfiles,
) : PastedKeySignIn {

    /** A key the server does not know is [ApiError.KeyRefused]; no key at all is too, and nothing is sent. */
    override suspend fun signInWithKey(serverUrl: String, key: String): ApiResult<Unit> {
        val pasted = key.trim()
        if (pasted.isEmpty()) return ApiResult.Failure(ApiError.KeyRefused)
        val server = api.forServer(serverUrl)
        return errors.call(KavitaCall.Key) {
            val account = server.account(key = pasted, cookie = null).answer().onRecentEnoughServer()
            val credential = Credential.ApiKey(key = pasted, keyId = idOf(server, pasted), ownedByApp = false)
            withContext(NonCancellable) { profiles.save(serverUrl, credential, account) }
        }
    }

    /**
     * The server's name for [key] among the account's keys, which it lists in clear; [UnnamedKeyId] when the list
     * cannot be read or does not hold it: the profile is saved all the same.
     */
    private suspend fun idOf(server: KavitaPublicApi, key: String): String {
        val listed = errors.call(KavitaCall.Other) { server.keys(key).answer() }
        val found = (listed as? ApiResult.Success)?.value?.firstOrNull { it.key == key }
        return found?.id?.toString() ?: UnnamedKeyId
    }
}
