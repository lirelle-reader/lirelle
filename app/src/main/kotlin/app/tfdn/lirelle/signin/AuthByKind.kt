package app.tfdn.lirelle.signin

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.awaitLoaded
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

/**
 * Signing in, whatever the kind of server: the app's [AuthRepository], over each kind's probe and sign-in.
 *
 * An address is asked of each kind in turn, in [probes]' order; the first that knows it is remembered with it.
 * A sign-in goes to the kind it is for, and a sign-out to the kind of the profile it ends: only that kind can end
 * its credential — as does the credential of a profile removed while it slept.
 *
 * @param probes each kind's probe, in the order they are asked.
 * @param signIns each kind's sign-in.
 */
@Suppress("TooManyFunctions") // One function per operation of the contract.
internal class AuthByKind(
    private val store: SessionStore,
    private val probes: List<Pair<ServerKind, ServerProbe>>,
    private val signIns: Map<ServerKind, SourceSignIn>,
) : AuthRepository {

    override val sessionState: StateFlow<SessionState> get() = store.state

    override val serverUrl: StateFlow<String?> get() = store.serverUrl

    override val serverKind: StateFlow<ServerKind?> get() = store.serverKind

    /**
     * A kind that could not get an answer leaves the next one to ask, and its failure is told when no kind knows
     * the address; one whose request never left tells it at once: the next kind would not get there either.
     */
    override suspend fun connectToServer(serverUrl: String): ApiResult<Unit> {
        var failure: ApiResult.Failure? = null
        for ((kind, probe) in probes) {
            when (val answer = probe.probe(serverUrl)) {
                is ApiResult.Success -> (answer.value as? ProbeAnswer.Mine)?.let { mine ->
                    return store.remember(mine.address, kind)
                }
                is ApiResult.Failure -> {
                    if (!answer.mayHaveReachedServer) return answer
                    failure = failure ?: answer
                }
            }
        }
        return failure ?: ApiResult.Failure(ApiError.UnknownServer)
    }

    override suspend fun loginOptions(serverUrl: String, kind: ServerKind): ApiResult<LoginOptions> =
        signIns.on(kind) { it.loginOptions(serverUrl) }

    override suspend fun signInWithPassword(
        serverUrl: String,
        kind: ServerKind,
        identifier: String,
        password: String,
    ): ApiResult<Unit> = signIns.on(kind) { it.signInWithPassword(serverUrl, identifier, password) }

    override suspend fun beginOidc(serverUrl: String, kind: ServerKind, provider: OidcProvider): ApiResult<String> =
        signIns.on(kind) { it.beginOidc(serverUrl, provider) }

    override suspend fun completeWithSession(serverUrl: String, kind: ServerKind, cookies: String): ApiResult<Unit> =
        signIns.on(kind) { it.completeWithSession(serverUrl, cookies) }

    /** To the kind whose sign-in waits for this redirect; refused, as a kind refuses it, when none does. */
    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> =
        signIns.values.firstOrNull { it.answersPendingOidc(redirectUri) }?.completeOidc(redirectUri)
            ?: ApiResult.Failure(ApiError.OidcRefused)

    override suspend fun hasPendingOidc(): Boolean = signIns.values.any { it.hasPendingOidc() }

    override suspend fun answersPendingOidc(redirectUri: String): Boolean =
        signIns.values.any { it.answersPendingOidc(redirectUri) }

    override suspend fun cancelOidc() = signIns.values.forEach { it.cancelOidc() }

    /**
     * The profile's kind is looked at before anything is removed: a kind without its sign-in here could not end
     * its credential, and the profile is left as it is.
     */
    override suspend fun signOut(profileId: String) {
        store.awaitLoaded()
        val kind = store.current.value?.takeIf { it.id == profileId }?.kind ?: return
        signIns[kind]?.signOut(profileId)
    }

    /** No profile is open: the kind of the profile removed is told, and nothing is done for a kind not here. */
    override suspend fun endCredential(kind: ServerKind, serverUrl: String, credential: Credential) {
        signIns[kind]?.endCredential(serverUrl, credential)
    }
}

/** [call] made to [kind]'s sign-in; an unknown server, nothing sent, for a kind without its sign-in here. */
private suspend fun <T> Map<ServerKind, SourceSignIn>.on(
    kind: ServerKind,
    call: suspend (SourceSignIn) -> ApiResult<T>,
): ApiResult<T> = get(kind)?.let { call(it) } ?: ApiResult.Failure(ApiError.UnknownServer)

/**
 * Remembers [address], a server of [kind]. A registry that cannot be written is told as a server out of reach, as
 * it was before the kinds: the screen offers to try again, and nothing is remembered. A cancellation goes on as it
 * is.
 */
private suspend fun SessionStore.remember(address: String, kind: ServerKind): ApiResult<Unit> = try {
    setServerUrl(address, kind)
    ApiResult.Success(Unit)
} catch (_: IOException) {
    ApiResult.Failure(ApiError.ServerUnreachable)
}
