package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto

/**
 * The saving of a Kavita profile, whichever key it holds: the device's own or one the user pasted.
 *
 * A key the app made that no profile could be saved with is deleted at once — a key no profile holds would stay
 * valid on the server for ever. For the same reason, the key of the profile an account added again replaces is
 * deleted once the new one is saved, when the app had made it.
 */
internal class KavitaProfiles(
    private val store: SessionStore,
    private val revocation: KavitaRevocation,
) {

    /**
     * Saves the profile of [account] on [serverUrl] with [credential], the device's key or one the user pasted,
     * then deletes the key the profile held before, when the account was added again ([endReplaced]). A key of
     * the app's that no profile could be saved with is deleted at once, and the failure goes on as it is.
     */
    suspend fun save(serverUrl: String, credential: Credential.ApiKey, account: KavitaUserDto) {
        var saved = false
        val replaced = try {
            store.save(Session(serverUrl, credential, account.toProfile()), ServerKind.Kavita).also { saved = true }
        } finally {
            if (!saved) revocation.revoke(serverUrl, credential)
        }
        endReplaced(serverUrl, replaced, credential)
    }

    /**
     * Deletes the key of the profile that [saved] replaced, with the new key: the same account, added again. Only
     * a key the app made ([KavitaRevocation.revoke]): one the user pasted is theirs. Not when the server had
     * refused it (the profile waited for a sign-in): it is deleted there already, or nothing can be done with it.
     * Nor when it is the very key saved — the user may paste the key the app once made.
     */
    private suspend fun endReplaced(serverUrl: String, replaced: ReplacedSession?, saved: Credential.ApiKey) {
        val old = replaced?.credential as? Credential.ApiKey ?: return
        if (replaced.refused || old.isSameSignIn(saved) || old.key == saved.key) return
        revocation.revoke(serverUrl, old, with = saved.key)
    }
}

/** The account as the app keeps it: Kavita names its accounts by their username, and its id is a number's text. */
internal fun KavitaUserDto.toProfile() = UserProfile(
    id = id.toString(),
    username = username,
    name = username,
    email = email,
)
