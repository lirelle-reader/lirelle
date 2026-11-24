package app.tfdn.lirelle.profile

import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.downloads.DownloadsFiles
import app.tfdn.lirelle.core.reader.ReadingFiles
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The field in which each value of the account's stores says whose it is. */
private const val Owner = "owner"

private val Format = Json { ignoreUnknownKeys = true }

/**
 * The account whose values the migration keeps: its server, and its id as the app names it, as text.
 */
internal data class StoredOwner(val serverUrl: String, val userId: String)

/**
 * Whose a value was, as the version before the profiles wrote it in each value of the account's stores
 * (`{"serverUrl":…,"userId":…}`): the account's id was a number then. Only the migration knows it now: the
 * stores of a profile name no owner, and check none.
 */
@Serializable
private data class WrittenOwner(val serverUrl: String, val userId: Long) {
    fun isOf(account: StoredOwner) = serverUrl == account.serverUrl && userId.toString() == account.userId
}

/**
 * The moved preferences files whose values each say whose they are, and the keys to drop from each whatever
 * they hold: the first format of the marks, which nothing reads.
 */
internal val OwnedStores = mapOf(
    ReadingFiles.Store to setOf(ReadingFiles.FirstMarks),
    ListeningFiles.Store to emptySet(),
    DownloadsFiles.Store to emptySet(),
)

/**
 * Removes from [store], once it is in the profile's folder, every value that is not [account]'s. The old
 * stores kept one account's data at a time and wrote its owner in each value: what another account left was
 * given to nobody, and dropped at the next write. In a profile it would be taken for the profile's own, so it
 * goes here. What is the account's is left exactly as it is.
 *
 * Only for a profile still being moved in, never for one that was opened: the stores of a profile write their
 * values without an owner, and those would all go here as nobody's ([isAnothers]).
 *
 * @param dropped the keys removed whatever they hold.
 */
internal suspend fun keepOnlyOf(account: StoredOwner, store: File, dropped: Set<String>) {
    if (!store.exists()) return
    store.opened { values ->
        val others = values.data.first().asMap().filter { (key, value) ->
            key.name in dropped || isAnothers(value, account)
        }
        if (others.isNotEmpty()) values.edit { stored -> others.keys.forEach { stored.remove(it) } }
    }
}

/**
 * Whether [value] is a store's value that is not [account]'s: an object whose owner is another account, or
 * that has none, which is nobody's. One whose owner cannot be understood is not known to be [account]'s
 * either, and goes too: the stores stop looking at the owner once a value is in a profile's folder, and would
 * read it as the profile's own. What is not an object at all is left for the store, which reads it as nothing.
 *
 * A value with no owner goes: that is right only because this is asked of files the old version wrote, in a
 * profile being moved in. A profile that was opened holds values of its own stores, which name no owner, and
 * must never be passed through here.
 */
private fun isAnothers(value: Any, account: StoredOwner): Boolean {
    val stored = (value as? String)?.let(::objectOrNull) ?: return false
    val owner = stored[Owner]?.takeUnless { it is JsonNull } ?: return true
    return try {
        !Format.decodeFromJsonElement(WrittenOwner.serializer(), owner).isOf(account)
    } catch (_: IllegalArgumentException) {
        true
    }
}

private fun objectOrNull(text: String): JsonObject? = try {
    Format.parseToJsonElement(text) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}
