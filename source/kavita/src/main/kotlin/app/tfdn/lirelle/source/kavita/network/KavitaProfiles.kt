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
 * deleted once the new one is saved, when the app had made it — even one the server had refused. And a key the
 * app made stays the app's when the user pastes it into the profile that holds it: Kavita lists every key in
 * clear, and the key must still be deleted when the profile goes.
 */
internal class KavitaProfiles(
    private val store: SessionStore,
    private val revocation: KavitaRevocation,
) {

    /**
     * Saves the profile of [account] on [serverUrl] with [credential], the device's key or one the user pasted,
     * then deletes the key the profile held before, when the account was added again ([endReplaced]). A key of
     * the app's that no profile could be saved with is deleted at once, and the failure goes on as it is.
     *
     * The registry tells what the profile held only once it is saved. When that was the app's own key and
     * [credential] is the same key, pasted, the profile is saved once more with the key as it held it — the app's,
     * under the name the server gave it — and nothing is deleted. Should that second save fail, the profile holds
     * the key as the user's, and the failure goes on.
     */
    suspend fun save(serverUrl: String, credential: Credential.ApiKey, account: KavitaUserDto) {
        var saved = false
        val replaced = try {
            store.save(Session(serverUrl, credential, account.toProfile()), ServerKind.Kavita).also { saved = true }
        } finally {
            if (!saved) revocation.revoke(serverUrl, credential)
        }
        val held = replaced?.credential as? Credential.ApiKey
        if (held != null && held.isAppsOwn(pasted = credential)) {
            store.save(Session(serverUrl, held, account.toProfile()), ServerKind.Kavita)
        } else {
            endReplaced(serverUrl, replaced, credential)
        }
    }

    /** Whether this key, which the app made, is the very key [pasted] by the user. */
    private fun Credential.ApiKey.isAppsOwn(pasted: Credential.ApiKey): Boolean =
        ownedByApp && !pasted.ownedByApp && key == pasted.key

    /**
     * Deletes the key of the profile that [saved] replaced, with the new key: the same account, added again. Only
     * a key the app made ([KavitaRevocation.revoke]): one the user pasted is theirs. Also when the server had
     * refused it (the profile waited for a sign-in): the refusal may have been a reverse proxy's, for a while, and
     * the key would stay valid for ever; deleted there already, the deletion fails and nothing is lost. Not when
     * it is the very key saved.
     */
    private suspend fun endReplaced(serverUrl: String, replaced: ReplacedSession?, saved: Credential.ApiKey) {
        val old = replaced?.credential as? Credential.ApiKey ?: return
        if (old.isSameSignIn(saved) || old.key == saved.key) return
        revocation.revoke(serverUrl, old, with = saved.key)
    }
}

private const val DownloadRole = "Download"
private const val AdminRole = "Admin"

/**
 * The account as the app keeps it: Kavita names its accounts by their username, and its id is a number's text. Its
 * download policy takes either the Download role or the Admin one; a server that tells no roles keeps the app's
 * default, which allows it.
 */
internal fun KavitaUserDto.toProfile() = UserProfile(
    id = id.toString(),
    username = username,
    name = username,
    email = email,
    canDownload = roles?.let { DownloadRole in it || AdminRole in it } ?: true,
)
