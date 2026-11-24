package app.tfdn.lirelle.core.session

import java.time.Instant

data class Tokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
) {
    /** Never prints the tokens: this text ends up in logs and in failed assertions. */
    override fun toString(): String =
        "Tokens(accessToken=<redacted>, accessTokenExpiresAt=$accessTokenExpiresAt, " +
            "refreshToken=<redacted>, refreshTokenExpiresAt=$refreshTokenExpiresAt)"
}

/**
 * @property id the account's id as the server gives it, as text: like a book's, the account is the server's, and
 *   its id means nothing outside it. Two sign-ins are of the same account when their texts are equal.
 * @property canDownload whether the server lets this account keep books on the device for offline reading. True
 *   until the server says otherwise: a session stored before the app asked may still try.
 * @property avatarUrl where the server keeps the account's picture, as the server gave it; null when the
 *   account has none, or was stored before the app asked.
 */
data class UserProfile(
    val id: String,
    val username: String,
    val name: String,
    val email: String?,
    val canDownload: Boolean = true,
    val avatarUrl: String? = null,
)

/** @property credential what the server takes from this account on this device, of the sort its kind holds. */
data class Session(
    val serverUrl: String,
    val credential: Credential,
    val user: UserProfile,
)

/**
 * Why a profile was removed. A session that expires is not among them: it removes nothing, its profile is
 * kept and marked ([Profile.signInNeeded]).
 */
enum class SignOutReason {
    UserRequest,
}

sealed interface SessionState {
    /** The stored session has not been read yet. */
    data object Loading : SessionState

    /**
     * [serverUrl] is the server remembered for the next sign-in, if any, as [SessionStore.serverUrl] gives it
     * whoever is signed in; [reason] is set only for the current process.
     */
    data class SignedOut(val serverUrl: String?, val reason: SignOutReason?) : SessionState

    data class SignedIn(val session: Session) : SessionState
}
