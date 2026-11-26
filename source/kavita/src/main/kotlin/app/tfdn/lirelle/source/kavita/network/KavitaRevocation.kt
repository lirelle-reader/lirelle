package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.NetworkDiagnostics
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.kindAndFrames
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * How long a deletion may take, from its connection to its last byte: the app has let go of the key already,
 * and a server that does not answer is not waited for.
 */
private val RevocationTimeout: Duration = Duration.ofSeconds(10)

/**
 * Deletes the device's keys on a Kavita server — whether a profile still holds them or not: a key lasts until it
 * is deleted, and one the app has let go of would otherwise stay valid for ever. Only the keys the app made: one
 * the user pasted is theirs, and nothing is sent for it.
 *
 * Through the sign-in's client, made from the public one ([kavitaSignInClient]): the key goes to its server only,
 * never along a redirect to another; and given up after [timeout].
 */
internal class KavitaRevocation(
    publicClient: OkHttpClient,
    json: Json,
    timeout: Duration = RevocationTimeout,
    private val log: () -> NetworkLog = { NetworkDiagnostics.log },
) {

    private val api = KavitaApis(
        kavitaSignInClient(publicClient).newBuilder().callTimeout(timeout).build(),
        json,
        KavitaPublicApi::class.java,
    )

    /**
     * Deletes [key] on [serverUrl] when the app made it, proven by [with] — the key itself, or another key of the
     * account — to the end even when the caller goes away, and at best: a server out of reach, or one that
     * refuses, keeps it, and nothing more can be done — the user can delete it in Kavita's web interface. A key
     * left valid is told to [log] (nowhere unless a debug build says so): by the answer's status and path, or by
     * the kind of what stopped the call; never the key, the server, nor what the server answered.
     */
    suspend fun revoke(serverUrl: String, key: Credential.ApiKey, with: String = key.key) {
        if (!key.ownedByApp) return
        withContext(NonCancellable) {
            val failure = runCatching {
                val answer = api.forServer(serverUrl).deleteKey(with, key.keyId)
                answer.errorBody()?.close()
                if (!answer.isSuccessful) {
                    log().warn("Kavita key not deleted: ${answer.code()} on ${answer.raw().request.url.encodedPath}")
                }
            }.exceptionOrNull() ?: return@withContext
            // Nothing the deletion throws may hide why it was made; a cancellation and what is no exception go on.
            if (failure is CancellationException || failure !is Exception) throw failure
            // Its kind only: an exception's message may name the server.
            log().warn("Kavita key not deleted: ${failure.kindAndFrames()}")
        }
    }
}
