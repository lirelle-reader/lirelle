package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileSession
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An Audiobookshelf profile's session held in memory, behaving like the real one: a write is in [session] when it
 * returns, once the profile is [gone] nothing is written any more, and an answer about a sign-in the profile no
 * longer holds changes nothing. The test sets [session] directly to sign in again.
 */
class FakeProfileSession(initial: Session) : ProfileSession {
    override val profileId = "p1"
    override val kind = ServerKind.Audiobookshelf
    override val session = MutableStateFlow(initial)

    /** The server refused to renew the session: set by [expire], and by a test, which also lowers it. */
    override val signInNeeded = MutableStateFlow(false)

    /** The profile was removed, or left for another. */
    @Volatile
    var gone = false

    override val open: Boolean get() = !gone

    /** The server refused to renew the session, and the profile was marked for it. */
    val expired: Boolean get() = signInNeeded.value

    override suspend fun updateCredential(credential: Credential, replacing: Credential) {
        if (!gone && holds(replacing)) session.update { it.copy(credential = credential) }
    }

    override suspend fun updateUser(user: UserProfile) {
        if (!gone) session.update { it.copy(user = user) }
    }

    override suspend fun expire(refused: Credential) {
        if (!gone && holds(refused)) signInNeeded.value = true
    }

    private fun holds(credential: Credential) = session.value.credential.isSameSignIn(credential)
}

/** Ann's account as the app keeps it. */
val Ann = UserProfile(id = AnnId, username = "ann", name = "ann", email = null)

/** The tokens a test signs in with: `access-1`, expiring at [accessExpiresAt], and `refresh-1`. */
fun absTokens(accessExpiresAt: Instant = AccessExpiry, access: String = "access-1", refresh: String = "refresh-1") =
    Tokens(access, accessExpiresAt, refresh, RefreshExpiry)

/** An Audiobookshelf session of Ann's account on [serverUrl], holding [tokens] under the sign-in [signInId]. */
fun absSession(serverUrl: String, tokens: Tokens = absTokens(), signInId: String = TheSignInId) =
    Session(serverUrl = serverUrl, credential = Credential.RotatingTokens(signInId, tokens), user = Ann)

/** The tokens [session] holds. */
val Session.tokens: Tokens get() = (credential as Credential.RotatingTokens).tokens

/** The access token a renewal gives, expiring an hour after [SignedInAt]. */
val RenewedAccess: String = annToken("access", AccessExpiry.plusSeconds(60))

/** The refresh token a renewal gives, which replaces the one sent. */
val RenewedRefresh: String = annToken("refresh", RefreshExpiry.plusSeconds(60))

/**
 * What Audiobookshelf answers to `POST auth/refresh`: the account, with the new pair — [accessToken] and
 * [refreshToken], each left out when null.
 */
fun refreshJson(accessToken: String? = RenewedAccess, refreshToken: String? = RenewedRefresh): String =
    loginJson(accessToken = accessToken, refreshToken = refreshToken)
