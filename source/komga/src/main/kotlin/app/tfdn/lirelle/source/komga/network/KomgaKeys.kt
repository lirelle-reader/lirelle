package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.deviceName
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.source.komga.network.dto.ApiKeyDto
import app.tfdn.lirelle.source.komga.network.dto.UserDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The device's own key, made with the session of a sign-in — by password or through a provider — and held by the
 * profile saved with it.
 *
 * Once the key is asked for, it may exist on the server: making it, ending the session and saving the profile go
 * on to the end even when the screen is left, and a profile that cannot be saved revokes its key at once — a key
 * no profile holds would stay valid on the server for ever. For the same reason, the key of the profile an account
 * added again replaces is revoked once the new one is saved.
 *
 * @param now and [zone] say when a key is made, in its comment.
 */
internal class KomgaKeys(
    private val store: SessionStore,
    private val revocation: KomgaRevocation,
    private val errors: KomgaErrors,
    private val deviceLabel: DeviceLabelProvider,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) {

    /**
     * Reads the account with [proof], then makes the device's key with its session ([keyFor]). An account that
     * cannot be read ends the session at best: one a provider opened exists already.
     */
    suspend fun signInWith(serverUrl: String, proof: KomgaSessionProof): ApiResult<Unit> {
        val account = when (val read = errors.call(proof.reading) { proof.account() }) {
            is ApiResult.Success -> read.value
            is ApiResult.Failure -> {
                withContext(NonCancellable) { proof.end() }
                return read
            }
        }
        return errors.call(KomgaCall.Mint) { keyFor(serverUrl, proof, account) }
    }

    /**
     * Makes the key with [proof]'s session, ends that session, and saves the profile of [account] on [serverUrl]
     * with the key, all to the end whatever happens to the caller.
     */
    private suspend fun keyFor(serverUrl: String, proof: KomgaSessionProof, account: UserDto) =
        withContext(NonCancellable) {
            val comment = keyComment(deviceLabel.deviceName(), LocalDateTime.ofInstant(now(), zone()))
            val key = try {
                proof.mint(comment)
            } finally {
                proof.end()
            }
            save(serverUrl, key, account)
        }

    @Suppress("TooGenericExceptionCaught") // Whatever stops the save, the key goes; the failure is told as it is.
    private suspend fun save(serverUrl: String, key: ApiKeyDto, account: UserDto) {
        val credential = Credential.ApiKey(key = key.key, keyId = key.id)
        val replaced = try {
            store.save(Session(serverUrl, credential, account.toProfile()), ServerKind.Komga)
        } catch (failure: Exception) {
            revocation.revoke(serverUrl, credential)
            throw failure
        }
        endReplaced(serverUrl, replaced, credential)
    }

    /**
     * Revokes the key of the profile that [saved] replaced: the same account, added again. Not when the server had
     * refused it (the profile waited for a sign-in): it is revoked there already, or nothing can be done with it.
     * Nor when it is the very key saved.
     */
    private suspend fun endReplaced(serverUrl: String, replaced: ReplacedSession?, saved: Credential.ApiKey) {
        val old = replaced?.credential as? Credential.ApiKey ?: return
        if (replaced.refused || old.isSameSignIn(saved)) return
        revocation.revoke(serverUrl, old)
    }
}
