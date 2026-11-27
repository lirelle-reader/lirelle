package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsUserDto
import java.time.Instant
import java.util.UUID

/** A new sign-in's id: a random UUID. */
internal val DrawSignInId: () -> String = { UUID.randomUUID().toString() }

/**
 * The saving of an Audiobookshelf profile, whichever way it signed in: its access and refresh tokens, their expiries
 * read from them at [now] ([AbsJwt]), under a sign-in id drawn by [newSignInId] — the refresh token changes at every
 * renewal, and cannot name the sign-in. The same account of the same server keeps its profile, as for every kind.
 */
internal class AbsProfiles(
    private val store: SessionStore,
    private val logout: AbsLogout,
    private val now: () -> Instant,
    private val newSignInId: () -> String,
) {

    /**
     * Saves the profile of [user] on [serverUrl] with the session's tokens, then closes on the server the session of
     * the same account that it replaced on this device: Audiobookshelf keeps it open otherwise, until it expires.
     * At best, once the new one is saved: whatever the server answers, the sign-in stands. Nothing is sent for a
     * session the server had refused already (the profile waited for a sign-in), nor for the very sign-in saved.
     *
     * An answer without both tokens — a server that ignored `x-return-tokens` — is unexpected, and nothing is saved.
     */
    suspend fun save(serverUrl: String, user: AbsUserDto) {
        val tokens = user.tokens(now()) ?: throw ApiErrorException(ApiError.Unexpected)
        val credential = Credential.RotatingTokens(newSignInId(), tokens)
        val replaced = store.save(Session(serverUrl, credential, user.toProfile()), ServerKind.Audiobookshelf) ?: return
        val old = replaced.credential as? Credential.RotatingTokens ?: return
        if (replaced.refused || old.isSameSignIn(credential)) return
        logout.close(serverUrl, old.tokens.refreshToken)
    }
}

/** The session's tokens, their expiries read from them ([AbsJwt]); null when the answer lacks either. */
private fun AbsUserDto.tokens(now: Instant): Tokens? {
    val access = accessToken?.takeIf { it.isNotBlank() } ?: return null
    val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: return null
    return Tokens(access, AbsJwt.accessExpiry(access, now), refresh, AbsJwt.refreshExpiry(refresh, now))
}

/**
 * The account as the app keeps it: Audiobookshelf names its accounts by their username, and its id is a UUID. An
 * account may download unless its permissions say it may not.
 */
internal fun AbsUserDto.toProfile() = UserProfile(
    id = id,
    username = username,
    name = username,
    email = null,
    canDownload = permissions?.download != false,
)
