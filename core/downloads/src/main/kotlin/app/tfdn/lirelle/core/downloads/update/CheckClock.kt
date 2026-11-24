package app.tfdn.lirelle.core.downloads.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.downloads.store.JsonValue
import java.time.Instant
import kotlinx.serialization.Serializable

/** When every kept book was last checked against the server. */
interface CheckClock {
    /** Null when no full pass was made. */
    suspend fun last(): Instant?

    suspend fun mark(at: Instant)
}

/** A value written before the profiles also names an `owner`, which is no longer read. */
@Serializable
internal data class StoredCheck(val atMillis: Long? = null)

/** Under a key of its own: the store may be the registry's. */
class DataStoreCheckClock(dataStore: DataStore<Preferences>) : CheckClock {
    private val value = JsonValue(dataStore, "update_check", StoredCheck.serializer(), StoredCheck())

    override suspend fun last(): Instant? = value.read().atMillis?.let(Instant::ofEpochMilli)

    override suspend fun mark(at: Instant) = value.change { StoredCheck(at.toEpochMilli()) }
}
