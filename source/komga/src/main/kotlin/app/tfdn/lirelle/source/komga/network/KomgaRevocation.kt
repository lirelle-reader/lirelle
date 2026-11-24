package app.tfdn.lirelle.source.komga.network

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
 * How long a revocation may take, from its connection to its last byte: the app has let go of the key already,
 * and a server that does not answer is not waited for.
 */
private val RevocationTimeout: Duration = Duration.ofSeconds(10)

/**
 * Revokes the device's keys on a Komga server, each with the key itself — whether a profile still holds it or not:
 * a key lasts until it is revoked, and one the app has let go of would otherwise stay valid for ever.
 *
 * Through the sign-in's client, made from the public one ([komgaSignInClient]): the key goes to its server only,
 * never along a redirect to another; and given up after [timeout].
 */
internal class KomgaRevocation(
    publicClient: OkHttpClient,
    json: Json,
    timeout: Duration = RevocationTimeout,
    private val log: () -> NetworkLog = { NetworkDiagnostics.log },
) {

    private val api = KomgaApis(
        komgaSignInClient(publicClient).newBuilder().callTimeout(timeout).build(),
        json,
        KomgaPublicApi::class.java,
    )

    /**
     * Revokes [key] on [serverUrl], to the end even when the caller goes away, and at best: a server out of reach,
     * or one that refuses, keeps it, and nothing more can be done — the user can revoke it in Komga's web interface.
     * A key left valid is told to [log] (nowhere unless a debug build says so): by the answer's status and path, or
     * by why no answer came; never the key, nor the server.
     */
    @Suppress("TooGenericExceptionCaught") // Nothing the revocation throws may hide why it was made.
    suspend fun revoke(serverUrl: String, key: Credential.ApiKey) = withContext(NonCancellable) {
        try {
            val answer = api.forServer(serverUrl).revokeKey(key.key, key.keyId)
            answer.errorBody()?.close()
            if (!answer.isSuccessful) {
                log().warn("Komga key not revoked: ${answer.code()} on ${answer.raw().request.url.encodedPath}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Its kind only: an exception's message may name the server.
            log().warn("Komga key not revoked: ${failure.kindAndFrames()}")
        }
        Unit
    }
}
