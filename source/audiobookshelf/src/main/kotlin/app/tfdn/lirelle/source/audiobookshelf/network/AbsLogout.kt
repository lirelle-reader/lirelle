package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.NetworkDiagnostics
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.session.kindAndFrames
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * How long a logout may take, from its connection to its last byte: the app has let go of the session already, and
 * a server that does not answer is not waited for.
 */
private val LogoutTimeout: Duration = Duration.ofSeconds(10)

/**
 * Closes sessions on an Audiobookshelf server — whether a profile still holds them or not: `POST logout` with the
 * session's refresh token, which the server then refuses. An access token already given keeps working until it
 * expires (up to an hour): the server does not tie it to its session.
 *
 * Through the sign-in's client, made from the public one ([absSignInClient]): the refresh token goes to its server
 * only, never along a redirect to another; and given up after [timeout].
 */
internal class AbsLogout(
    publicClient: OkHttpClient,
    timeout: Duration = LogoutTimeout,
    private val log: () -> NetworkLog = { NetworkDiagnostics.log },
) {

    private val api = AbsApis(
        absSignInClient(publicClient).newBuilder().callTimeout(timeout).build(),
        AbsJson,
        AbsPublicApi::class.java,
    )

    /**
     * Closes the session of [refreshToken] on [serverUrl], to the end even when the caller goes away, and at best: a
     * server out of reach, or one that refuses, keeps it until it expires, and nothing more can be done. A session
     * left open is told to [log] (nowhere unless a debug build says so): by the answer's status, or by the kind of
     * what stopped the call; never the token, the server, nor what the server answered.
     */
    suspend fun close(serverUrl: String, refreshToken: String) {
        withContext(NonCancellable) {
            val failure = runCatching {
                val answer = api.forServer(serverUrl).logout(refreshToken)
                answer.errorBody()?.close()
                if (!answer.isSuccessful) log().warn("Audiobookshelf session not closed: ${answer.code()}")
            }.exceptionOrNull() ?: return@withContext
            // Nothing the logout throws may hide why it was made; a cancellation and what is no exception go on.
            if (failure is CancellationException || failure !is Exception) throw failure
            // Its kind only: an exception's message may name the server.
            log().warn("Audiobookshelf session not closed: ${failure.kindAndFrames()}")
        }
    }
}
