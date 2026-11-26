package app.tfdn.lirelle.core.session

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What the registry writes: every profile of the device, and the id of the one the app shows.
 *
 * @property profiles the entries this version understands, in order of use, the most recent first: a profile
 *   that becomes the current one moves to the front. No date is kept: only the order is ever asked for.
 * @property current the profile the app shows. The first of [profiles] whenever a registry that keeps the
 *   order wrote it; still written, and read before the order, for a file from before the order was kept.
 * @property unread the entries it does not, written back as they were found.
 */
internal data class StoredRegistry(
    val profiles: List<StoredProfile> = emptyList(),
    val current: String? = null,
    val unread: List<UnreadEntry> = emptyList(),
)

/**
 * An entry of the registry that this version cannot understand: a kind of server it does not know, a field
 * missing or of another shape. Most likely written by a later version, so it is somebody's profile: it stays
 * in the file as it is, and its folders are its own. What cannot be understood is never deleted.
 *
 * @property id the profile it names, when it names one.
 * @property raw the entry as it was found, which is what is written back.
 */
internal data class UnreadEntry(val id: String?, val raw: JsonElement)

/**
 * @property kind the name of a [ServerKind]; text, so that a kind this version does not know is one profile
 *   that cannot be read rather than a registry that cannot.
 * @property state one of [StoredState]. Only a `ready` profile is ever opened.
 * @property signInNeeded [Profile.signInNeeded]. Not a state: such a profile is ready. Absent from what was
 *   stored before it existed, and from every profile that is not marked: it reads as false.
 * @property dataFormat [Profile.dataFormat]. Absent from what was stored before it existed: that profile's
 *   folders hold data of the old format ([OldDataFormat]).
 */
@Serializable
internal data class StoredProfile(
    val id: String,
    val kind: String,
    val session: StoredSession,
    val state: String = StoredState.Ready,
    val signInNeeded: Boolean = false,
    val dataFormat: Int? = null,
)

internal object StoredState {
    const val Ready = "ready"

    /** Listed so that its folders are somebody's, while the migration is still filling them. */
    const val Moving = "moving"

    /** Signed out, or unreadable: its folders are to be deleted, then its record dropped. */
    const val Removing = "removing"
}

/**
 * A session carries its own server address: its credential is never paired with an address written separately.
 *
 * The shape of the time when a profile could only hold BookOrbit's tokens, kept so that those entries stay as
 * they are: what [encryptedTokens] holds is the credential, of the sort the profile's kind says
 * ([ServerKind.credentialFormat]), read once the kind is known.
 *
 * @property sessionId [Credential.SessionTokens.sessionId]; absent for a credential without one.
 */
@Serializable
internal data class StoredSession(
    val serverUrl: String,
    val sessionId: Long? = null,
    val user: StoredUser,
    val encryptedTokens: String,
)

/** The sorts of [Credential], as the stored entry holds them. */
internal enum class CredentialFormat { SessionTokens, ApiKey }

/** The sort of credential a profile of this kind holds. */
internal val ServerKind.credentialFormat: CredentialFormat
    get() = when (this) {
        ServerKind.BookOrbit -> CredentialFormat.SessionTokens
        ServerKind.Komga, ServerKind.Kavita -> CredentialFormat.ApiKey
    }

internal val Credential.format: CredentialFormat
    get() = when (this) {
        is Credential.SessionTokens -> CredentialFormat.SessionTokens
        is Credential.ApiKey -> CredentialFormat.ApiKey
    }

/**
 * [canDownload] is absent from sessions stored before it existed: they read as allowed. [avatarUrl] likewise,
 * and they read as having no picture. [id] was a number before the account's id became text: it reads as its
 * text either way ([AccountIdSerializer]), and is written as text.
 */
@Serializable
internal data class StoredUser(
    @Serializable(with = AccountIdSerializer::class)
    val id: String,
    val username: String,
    val name: String,
    val email: String? = null,
    val canDownload: Boolean = true,
    val avatarUrl: String? = null,
)

internal fun UserProfile.toStored() = StoredUser(id, username, name, email, canDownload, avatarUrl)

internal fun StoredUser.toProfile() = UserProfile(id, username, name, email, canDownload, avatarUrl)

/** Only ever written encrypted: as it is, it holds the tokens in clear. */
@Serializable
internal data class StoredTokens(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
) {
    /** Never prints the tokens: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "StoredTokens(<redacted>)"
}

internal fun Tokens.toStored() = StoredTokens(
    accessToken = accessToken,
    accessTokenExpiresAt = accessTokenExpiresAt.toString(),
    refreshToken = refreshToken,
    refreshTokenExpiresAt = refreshTokenExpiresAt.toString(),
)

internal fun StoredTokens.toTokens() = Tokens(
    accessToken = accessToken,
    accessTokenExpiresAt = Instant.parse(accessTokenExpiresAt),
    refreshToken = refreshToken,
    refreshTokenExpiresAt = Instant.parse(refreshTokenExpiresAt),
)

/**
 * Only ever written encrypted: as it is, it holds the key in clear.
 *
 * @property own [Credential.ApiKey.ownedByApp]. Absent from what was stored before it existed, when every key was
 *   one the app had made, and from every key that is the app's own: it reads as true.
 */
@Serializable
internal data class StoredApiKey(val key: String, val keyId: String, val own: Boolean = true) {
    /** Never prints the key: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "StoredApiKey(<redacted>)"
}

/** Turns sessions and profiles into what is stored and back. The credentials are encrypted with [cipher]. */
internal class ProfileCodec(private val cipher: TokenCipher) {

    fun encode(session: Session): StoredSession {
        val credential = session.credential
        val plain = when (credential) {
            is Credential.SessionTokens -> json.encodeToString(credential.tokens.toStored())
            is Credential.ApiKey ->
                json.encodeToString(StoredApiKey(credential.key, credential.keyId, credential.ownedByApp))
        }
        return StoredSession(
            serverUrl = session.serverUrl,
            sessionId = (credential as? Credential.SessionTokens)?.sessionId,
            user = session.user.toStored(),
            encryptedTokens = cipher.encrypt(plain),
        )
    }

    /**
     * Returns null for anything that is not a complete, readable session holding a credential of [format].
     *
     * @param certain for who deletes what an unreadable session owned — the migration: null then means that
     *   the session can never be read again ([TokenCipher.decryptOrThrow]).
     * @throws java.io.IOException with [certain], when the credential could not be decrypted for now.
     */
    fun decode(stored: StoredSession, format: CredentialFormat, certain: Boolean = false): Session? {
        val encrypted = stored.encryptedTokens
        val plain = (if (certain) cipher.decryptOrThrow(encrypted) else cipher.decrypt(encrypted)) ?: return null
        return stored.read(plain, format)
    }

    fun encode(profile: Profile, state: String = StoredState.Ready) = StoredProfile(
        profile.id,
        profile.kind.name,
        encode(profile.session),
        state,
        profile.signInNeeded,
        profile.dataFormat,
    )

    /**
     * Returns null when the session cannot be read, or the kind of server is not one this version knows.
     * [certain] as for a session.
     */
    fun decode(stored: StoredProfile, certain: Boolean = false): Profile? {
        val kind = stored.serverKind() ?: return null
        return decode(stored.session, kind.credentialFormat, certain)?.let { stored.toProfile(kind, it) }
    }

    /**
     * What the first read makes of [stored], said ready: its credential decrypted once, as the migration does
     * ([decode]'s `certain`), then read as its kind says or, failing that, as any other sort — on the same text,
     * so that the keystore is asked once, as for any other entry.
     *
     * @throws java.io.IOException when the credential could not be decrypted for now: the entry is not to be
     *   judged at this start.
     */
    fun decodeReady(stored: StoredProfile): ReadyEntry {
        val kind = stored.serverKind() ?: return ReadyEntry.Lost
        val plain = cipher.decryptOrThrow(stored.session.encryptedTokens) ?: return ReadyEntry.Lost
        stored.session.read(plain, kind.credentialFormat)?.let { return ReadyEntry.Opened(stored.toProfile(kind, it)) }
        // Only the sort is asked here: a session id the other sort would need is not what is missing.
        val anyId = stored.session.copy(sessionId = stored.session.sessionId ?: 0)
        val other = CredentialFormat.entries.any { it != kind.credentialFormat && anyId.read(plain, it) != null }
        return if (other) ReadyEntry.OtherSort else ReadyEntry.Lost
    }

    fun encode(registry: StoredRegistry): String = buildJsonObject {
        val understood = registry.profiles.map { json.encodeToJsonElement(it) }
        put(Profiles, JsonArray(understood + registry.unread.map { it.raw }))
        registry.current?.let { put(Current, it) }
    }.toString()

    /**
     * Returns null for text that is not a registry. Read entry by entry: one that cannot be understood is one
     * profile set aside ([StoredRegistry.unread]), not a registry lost with every other profile in it.
     */
    fun decodeRegistry(raw: String): StoredRegistry? = try {
        val registry = json.parseToJsonElement(raw).jsonObject
        val entries = registry[Profiles]?.jsonArray.orEmpty().map { it to understood(it) }
        StoredRegistry(
            profiles = entries.mapNotNull { (_, profile) -> profile },
            current = registry[Current].text(),
            unread = entries.filter { (_, profile) -> profile == null }
                .map { (entry, _) -> UnreadEntry((entry as? JsonObject)?.get(Id).text(), entry) },
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encodeSession(session: Session): String = json.encodeToString(encode(session))

    /**
     * Returns null for text that is not a complete, readable session. Only ever BookOrbit's: what the app kept
     * before the profiles. [certain] as for a stored session.
     */
    fun decodeSession(raw: String, certain: Boolean = false): Session? = try {
        decode(json.decodeFromString<StoredSession>(raw), CredentialFormat.SessionTokens, certain)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val Profiles = "profiles"
        const val Current = "current"
        const val Id = "id"
    }
}

/** What the first read makes of a profile said ready ([ProfileCodec.decodeReady]). */
internal sealed interface ReadyEntry {
    data class Opened(val profile: Profile) : ReadyEntry

    /**
     * Its credential is readable, but of another sort than its kind's: written by a version that went wrong, not
     * lost. Left as it is written, neither opened nor removed — its folders are still its account's.
     */
    data object OtherSort : ReadyEntry

    /** It can never be read again. */
    data object Lost : ReadyEntry
}

private val json = Json { ignoreUnknownKeys = true }

private fun StoredProfile.serverKind(): ServerKind? = ServerKind.entries.firstOrNull { it.name == kind }

private fun StoredProfile.toProfile(kind: ServerKind, session: Session) =
    Profile(id, kind, session, signInNeeded, dataFormat ?: OldDataFormat)

/**
 * The session this makes with [plain], its credential decrypted, read as [format]; null when [plain] is not a
 * credential of that sort, or one of its parts is missing.
 */
private fun StoredSession.read(plain: String, format: CredentialFormat): Session? = try {
    val credential = when (format) {
        CredentialFormat.SessionTokens -> sessionId?.let {
            Credential.SessionTokens(it, json.decodeFromString<StoredTokens>(plain).toTokens())
        }
        CredentialFormat.ApiKey ->
            json.decodeFromString<StoredApiKey>(plain).let { Credential.ApiKey(it.key, it.keyId, it.own) }
    }
    credential?.let { Session(serverUrl, it, user.toProfile()) }
} catch (_: IllegalArgumentException) {
    // Malformed JSON: SerializationException is an IllegalArgumentException.
    null
} catch (_: DateTimeParseException) {
    null
}

/**
 * The profile [entry] holds; null when it is not one this version can do anything with: a kind it does not
 * know, or an entry without what its kind's credential needs in clear — a BookOrbit session's id.
 */
private fun understood(entry: JsonElement): StoredProfile? = try {
    json.decodeFromJsonElement<StoredProfile>(entry).takeIf { stored ->
        val kind = stored.serverKind()
        val needsSessionId = kind?.credentialFormat == CredentialFormat.SessionTokens
        kind != null && (!needsSessionId || stored.session.sessionId != null)
    }
} catch (_: IllegalArgumentException) {
    null
}

/** The text this holds; null when it is absent, or anything but text. */
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
