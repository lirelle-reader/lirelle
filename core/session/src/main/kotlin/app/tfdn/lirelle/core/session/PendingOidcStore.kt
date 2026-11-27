package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the app must remember between opening the browser and receiving the OIDC redirect.
 *
 * @property begunAtMillis when the sign-in was begun, in milliseconds since the epoch: a sign-in does not wait
 *   for ever, or a browser tab finished much later would sign somebody in behind the user's back. Null in a
 *   request stored before the time was kept, which is then too old to be completed.
 * @property redirectUri the return address the server accepted for this sign-in, when it took one from the app
 *   (Audiobookshelf's); null for a sign-in whose return address is the source's own (BookOrbit's), and in a
 *   request stored before it was kept.
 * @property cookies the cookies the server set on the sign-in's first call, as one `Cookie` header, to be sent
 *   with the code's exchange (Audiobookshelf's); null when there were none, and in a request stored before.
 */
@Serializable
data class PendingOidcRequest(
    val serverUrl: String,
    val providerSlug: String,
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val begunAtMillis: Long? = null,
    val redirectUri: String? = null,
    val cookies: String? = null,
) {
    /** Never prints the secrets: this text ends up in logs and in failed assertions. */
    override fun toString(): String =
        "PendingOidcRequest(serverUrl=$serverUrl, providerSlug=$providerSlug, " +
            "state=<redacted>, codeVerifier=<redacted>, nonce=<redacted>, begunAtMillis=$begunAtMillis, " +
            "redirectUri=$redirectUri, cookies=${cookies?.let { "<redacted>" }})"
}

interface PendingOidcStore {
    suspend fun save(request: PendingOidcRequest)

    /**
     * Returns the pending request and forgets it, when [meant] says it is the one asked about: a redirect can
     * be used once. A request that is not the one meant stays where it is, still waiting for its own redirect:
     * anything can send the app a redirect, and one that answers nothing must not end a sign-in in progress.
     * Looked at and removed in one transaction, so only one caller can get the request.
     */
    suspend fun takeWhen(meant: (PendingOidcRequest) -> Boolean): PendingOidcRequest?

    /** The pending request, left where it is; null when none waits, or when it can no longer be read. */
    suspend fun peek(): PendingOidcRequest?

    /** Tells whether a request is waiting for its redirect, without using it up. */
    suspend fun isPending(): Boolean

    suspend fun clear()
}

/** Persisted so the request survives the app being killed while the browser is in front. */
class DataStorePendingOidcStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : PendingOidcStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(request: PendingOidcRequest) {
        dataStore.edit { it[Key] = cipher.encrypt(json.encodeToString(request)) }
    }

    override suspend fun takeWhen(meant: (PendingOidcRequest) -> Boolean): PendingOidcRequest? {
        var taken: PendingOidcRequest? = null
        dataStore.edit { stored ->
            val request = stored[Key]?.let(::decode)
            taken = request?.takeIf(meant)
            // What can no longer be read goes too: it answers no redirect, and never will.
            if (request == null || taken != null) stored.remove(Key)
        }
        return taken
    }

    override suspend fun peek(): PendingOidcRequest? = dataStore.data.first()[Key]?.let(::decode)

    private fun decode(raw: String): PendingOidcRequest? {
        val decrypted = cipher.decrypt(raw) ?: return null
        return try {
            json.decodeFromString<PendingOidcRequest>(decrypted)
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun isPending(): Boolean = dataStore.data.first().contains(Key)

    override suspend fun clear() {
        dataStore.edit { it.remove(Key) }
    }

    private companion object {
        val Key = PendingOidcKey
    }
}

/**
 * Where the pending request is kept; the same key in the `session` file of before the profiles. In the
 * registry's own file: a sign-in that is saved forgets it in the same write ([ProfileLedger.changed]).
 */
internal val PendingOidcKey = stringPreferencesKey("pending_oidc")
