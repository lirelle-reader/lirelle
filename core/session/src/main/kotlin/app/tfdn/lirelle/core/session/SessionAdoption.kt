package app.tfdn.lirelle.core.session

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job

/**
 * A profile the migration is still filling. Its id names its folders; its server and user say whose the old
 * files must be to be kept. Taken from what is stored in clear: it needs no token. The user is the account's id
 * as text ([UserProfile.id]).
 */
data class MovingProfile(val id: String, val serverUrl: String, val userId: String)

/**
 * What the `session` file of an installation from before the profiles held.
 *
 * @property session the account that was signed in; null when nobody was, or when its tokens can no longer be
 *   read, which is nobody too.
 */
class OldSession internal constructor(
    val session: Session?,
    internal val serverUrl: String?,
    internal val pendingOidc: String?,
)

internal fun StoredRegistry.moving(): MovingProfile? = profiles.firstOrNull { it.state == StoredState.Moving }
    ?.let { MovingProfile(it.id, it.session.serverUrl, it.session.user.id) }

/**
 * The registry's part in the migration of an installation from before the profiles, which kept one session in
 * a file of its own. The old file is the proof that a migration is due: it is deleted last ([finish]), so
 * that a start interrupted anywhere finds it again, and with it either no registry yet or a profile still
 * being moved in.
 *
 * The registry's file is first written by [adopt], whole: a profile being moved in, or nobody. So a registry
 * that exists beside the old file, without such a profile, has nothing left to do but [finish].
 */
class SessionAdoption internal constructor(
    private val sessionFile: File,
    private val registryFile: File,
    private val ledger: ProfileLedger,
    private val newId: () -> String,
) {
    /** Whether the old file is still there: a migration has to start, or to end. */
    fun due(): Boolean = sessionFile.exists()

    /** Whether the old session was taken already, by an earlier start. */
    fun started(): Boolean = registryFile.exists()

    /**
     * The profile an earlier start left being moved in; null when it left none.
     *
     * @throws IOException when the registry could not be read at this start: what it lists is not known.
     */
    suspend fun moving(): MovingProfile? {
        ledger.load()
        val registry = ledger.loaded()
        if (registry.unreadable) throw IOException("The profiles could not be read")
        return registry.stored.moving()
    }

    /**
     * Reads the old file, and lets go of it. A file that cannot be parsed holds nobody, as it did for the
     * version that wrote it.
     *
     * Whoever finds nobody deletes what the account kept, for good: so nobody is only said of a session that
     * can never be read again. A keystore that fails for now throws instead, and the next start asks again.
     *
     * @throws IOException when it could not be read, or its tokens could not be decrypted for now.
     */
    suspend fun read(): OldSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stored = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
            ) { sessionFile }.data.first()
            val session = stored[SessionKey]?.let { ledger.codec.decodeSession(it, certain = true) }
            return OldSession(session, stored[ServerUrlKey], stored[PendingOidcKey])
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    /**
     * Writes the registry from [old], in one write: its session as a profile being moved in, or nobody; the
     * server it last used and a sign-in waiting for its redirect are kept either way.
     *
     * @return the profile to move the old files into; null when nobody was signed in.
     */
    suspend fun adopt(old: OldSession): MovingProfile? {
        ledger.load()
        return ledger.changed(old.pendingOidc) { it.adopting(old, newId, ledger.codec) }?.stored?.moving()
    }

    /**
     * The profile [id] has all its files: it becomes ready, and the current one.
     *
     * @throws IOException when its tokens could not be decrypted for now: it stays being moved in.
     */
    suspend fun settle(id: String) = ledger.change { it.settling(id, ledger.codec) }

    /**
     * Deletes the old file: the migration is over, and no start looks at it again. What a write cut short
     * may have left beside it — DataStore's scratch file — goes first, as best as can be: once the file is
     * gone nothing comes back for it.
     *
     * @throws IOException when the file is still there.
     */
    fun finish() {
        val scratch = "${sessionFile.name}."
        sessionFile.parentFile?.listFiles { sibling -> sibling.name.startsWith(scratch) }
            ?.forEach { it.deleteRecursively() }
        if (sessionFile.exists() && !sessionFile.delete()) throw IOException("The old session file is still there")
    }

    private companion object {
        val SessionKey = stringPreferencesKey("session")
        val ServerUrlKey = stringPreferencesKey("server_url")
    }
}
