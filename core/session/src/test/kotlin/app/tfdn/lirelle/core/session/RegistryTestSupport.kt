package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** What the tests of the registry sign in with. */
internal object Sample {
    const val Server = "https://books.example.com"

    val tokens = Tokens(
        accessToken = "access-1",
        accessTokenExpiresAt = Instant.parse("2026-11-13T08:15:00Z"),
        refreshToken = "refresh-1",
        refreshTokenExpiresAt = Instant.parse("2026-11-20T08:00:00Z"),
    )
    val session = Session(
        serverUrl = Server,
        credential = Credential.SessionTokens(sessionId = 42, tokens),
        user = UserProfile(id = "1", username = "reader", name = "Ann Reader", email = "ann@example.com"),
    )
    val otherSession = session.copyCredential(sessionId = 43).copy(
        user = UserProfile(id = "2", username = "other", name = "Bob Other", email = null),
    )
}

/** The tokens of a BookOrbit session, which every session of these tests is. */
internal val Session.tokens: Tokens get() = (credential as Credential.SessionTokens).tokens

/** [this] BookOrbit session with another session id or other tokens. */
internal fun Session.copyCredential(
    sessionId: Long = (credential as Credential.SessionTokens).sessionId,
    tokens: Tokens = this.tokens,
): Session = copy(credential = Credential.SessionTokens(sessionId, tokens))

/** Signs [session] in as a BookOrbit one, which every session of these tests is. */
internal suspend fun SessionStore.save(session: Session): ReplacedSession? = save(session, ServerKind.BookOrbit)

/** Remembers [serverUrl] as a BookOrbit server's, which every server of these tests is. */
internal suspend fun SessionStore.setServerUrl(serverUrl: String) = setServerUrl(serverUrl, ServerKind.BookOrbit)

/** [refused] in the place of the BookOrbit tokens of the profile [id], as a renewal refused says. */
internal suspend fun ProfileStore.needSignIn(id: String, refused: Tokens): Profile? =
    needSignIn(id, Credential.SessionTokens(0, refused))

/** [tokens] in the place of [replacing], as a renewal of BookOrbit tokens writes them. */
internal suspend fun ProfileStore.updateTokens(id: String, tokens: Tokens, replacing: Tokens): Profile? {
    val sessionId = (profile(id)?.session?.credential as? Credential.SessionTokens)?.sessionId ?: 0
    return updateCredential(id, Credential.SessionTokens(sessionId, tokens), Credential.SessionTokens(0, replacing))
}

/**
 * Signs out of whoever is current at this moment, as a sign-out does that has just read the current profile;
 * nothing when nobody is.
 */
internal suspend fun SessionStore.clearCurrent(reason: SignOutReason) {
    current.value?.let { clear(it.id, reason) }
}

/**
 * Removes the profile [id] whatever it is at this moment, for the tests that set a scene and do not care
 * which: by the sign-out when it is the current one, as one that sleeps otherwise. The registry itself offers
 * no removal that does not know which of the two it is; [reason] is the sign-out's, a profile that sleeps
 * leaving somebody signed in.
 */
internal suspend fun <S> S.remove(id: String, reason: SignOutReason) where S : SessionStore, S : ProfileStore {
    if (!clear(id, reason)) removeSleeping(id)
}

/** The keys the registry writes under, for the tests that look at the file or alter it. */
internal object RegistryKeys {
    val Profiles = stringPreferencesKey("profiles")
    val ServerUrl = stringPreferencesKey("server_url")
    val ServerKind = stringPreferencesKey("server_kind")
}

/**
 * Writes a registry that lists one ready profile for each of [sessions] — p0, p1… — in the order they are
 * given, without a registry having to sign each of them in.
 *
 * @param current which of them the app shows; the last one when not said.
 */
internal suspend fun DataStore<Preferences>.writeReady(vararg sessions: Session, current: Int = sessions.lastIndex) {
    val codec = ProfileCodec(FakeTokenCipher())
    val profiles = sessions.mapIndexed { index, session ->
        codec.encode(Profile("p$index", ServerKind.BookOrbit, session))
    }
    edit {
        it[RegistryKeys.Profiles] = codec.encode(StoredRegistry(profiles, current = profiles[current].id))
        it[RegistryKeys.ServerUrl] = sessions[current].serverUrl
    }
}

/**
 * Counts the writes, lets a test stop the next one just before it reaches the file or just after, and makes
 * every write fail when [failWrites] is set, or one read when [failNextRead] is.
 */
internal class GatedDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    @Volatile
    var beforeNextWrite: (suspend () -> Unit)? = null

    @Volatile
    var afterNextWrite: (suspend () -> Unit)? = null

    @Volatile
    var failWrites = false

    @Volatile
    var writes = 0
        private set

    /** The next read fails, and that one only: the file is there, and could not be read this once. */
    @Volatile
    var failNextRead = false

    override val data: Flow<Preferences>
        get() = flow {
            if (failNextRead) {
                failNextRead = false
                throw IOException("cannot read")
            }
            emitAll(delegate.data)
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("cannot write")
        writes++
        beforeNextWrite?.also { beforeNextWrite = null }?.invoke()
        val written = delegate.updateData(transform)
        afterNextWrite?.also { afterNextWrite = null }?.invoke()
        return written
    }
}

/** A file that cannot be read at all, nor written. */
internal class UnreadableDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("unreadable") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("unreadable")
}

/**
 * A renewal of the tokens the profile [id] holds now: what every renewal's answer is about, unless its account
 * signed in again while it was on its way — which the tests that mean it say by naming the tokens themselves.
 */
internal suspend fun ProfileStore.renew(id: String, tokens: Tokens): Profile? =
    updateTokens(id, tokens, replacing = profile(id)?.session?.tokens ?: tokens)

/** Likewise, [credential] in the place of the one the profile [id] holds now. */
internal suspend fun ProfileStore.renew(id: String, credential: Credential): Profile? =
    updateCredential(id, credential, replacing = profile(id)?.session?.credential ?: credential)
