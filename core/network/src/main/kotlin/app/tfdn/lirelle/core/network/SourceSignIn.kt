package app.tfdn.lirelle.core.network

import app.tfdn.lirelle.core.session.Credential

/** What a source makes of an address: one of its servers, at the address that answers, or not one. */
sealed interface ProbeAnswer {
    /**
     * @property address where the server answers, to remember and sign in to: the typed address, or the one a
     *   redirect led to — keeping a base path, never from `https` to cleartext.
     */
    data class Mine(val address: String) : ProbeAnswer

    data object NotMine : ProbeAnswer
}

/**
 * How a source tells one of its servers from any other, before anyone is signed in to it. Bound for each kind
 * of server (`@SourceOf(kind)`, in the singleton component); the app asks each kind in turn.
 */
fun interface ServerProbe {
    /**
     * A failure when the server could not be asked — no network, no answer, a failing server: nothing is
     * known of it then. An answer that is not this kind's is [ProbeAnswer.NotMine], not a failure.
     */
    suspend fun probe(address: String): ApiResult<ProbeAnswer>
}

/**
 * Signing in to a server of one kind, and signing out of a profile of that kind: what [AuthRepository] sends
 * there. Bound for each kind of server (`@SourceOf(kind)`, in the singleton component). A new profile is saved
 * with this kind.
 */
interface SourceSignIn {
    suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions>

    suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit>

    /** As [AuthRepository.beginOidc]: for a provider that signs in through the browser. */
    suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<String>

    /** As [AuthRepository.completeOidc]. */
    suspend fun completeOidc(redirectUri: String): ApiResult<Unit>

    /**
     * As [AuthRepository.completeWithSession]: refused, nothing sent, by a kind whose providers never sign in
     * through a WebView.
     */
    suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit>

    /** As [AuthRepository.hasPendingOidc]: false for a kind that never signs in through the browser. */
    suspend fun hasPendingOidc(): Boolean

    /** As [AuthRepository.answersPendingOidc]. */
    suspend fun answersPendingOidc(redirectUri: String): Boolean

    /** As [AuthRepository.cancelOidc]. */
    suspend fun cancelOidc()

    /**
     * As [AuthRepository.signOut], for a profile of this kind only: one of another kind is left as it is,
     * neither removed nor its credential sent anywhere — this kind could not end it.
     */
    suspend fun signOut(profileId: String)

    /** As [AuthRepository.endCredential], for a credential of this kind. */
    suspend fun endCredential(serverUrl: String, credential: Credential)
}
