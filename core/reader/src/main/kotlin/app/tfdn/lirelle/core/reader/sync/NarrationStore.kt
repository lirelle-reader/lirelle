package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.reader.store.JsonValue
import java.time.Instant

/**
 * Where the narration of a read-along book is, as this device knows it. It is kept apart from the
 * text position of the same file ([LocalPosition]): the server keeps both.
 *
 * @property percent from 0 to 100.
 * @property at when the narration got there, by the device's clock.
 */
data class LocalNarration(val fileId: FileId, val place: NarrationPlace, val percent: Double, val at: Instant)

/**
 * The last known narration of each file, kept across restarts so that a read-along book resumes on
 * the right sentence without the network.
 */
interface NarrationStore {
    suspend fun get(fileId: FileId): LocalNarration?

    suspend fun put(n: LocalNarration)
}

class DataStoreNarrationStore(dataStore: DataStore<Preferences>) : NarrationStore {
    private val value = JsonValue(dataStore, "narrations", StoredNarrations.serializer(), StoredNarrations())

    override suspend fun get(fileId: FileId): LocalNarration? =
        value.read().narrations.lastOrNull { it.fileId == fileId }?.narration()

    override suspend fun put(n: LocalNarration) = value.change { stored ->
        StoredNarrations(stored.narrations.filterNot { it.fileId == n.fileId } + n.stored())
    }
}
