package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.store.JsonValue
import java.time.Instant

/**
 * Where the user is in a file, as this device knows it.
 *
 * @property cfi the place in a book; null when only the percentage is known, and for a file read by pages.
 * @property percent from 0 to 100.
 * @property at when the user got there, by the device's clock.
 * @property page the page of a file read by pages (a comic), as the server counts it; null for a book.
 */
data class LocalPosition(
    val fileId: FileId,
    val cfi: String?,
    val percent: Double,
    val at: Instant,
    val page: Int? = null,
)

/**
 * The last known position of each file, kept across restarts so that a book reopens on the right
 * page without the network.
 */
interface PositionStore {
    suspend fun get(fileId: FileId): LocalPosition?

    suspend fun put(position: LocalPosition)
}

class DataStorePositionStore(dataStore: DataStore<Preferences>) : PositionStore {
    private val value = JsonValue(dataStore, "positions", StoredPositions.serializer(), StoredPositions())

    override suspend fun get(fileId: FileId): LocalPosition? =
        value.read().positions.lastOrNull { it.fileId == fileId }?.position()

    override suspend fun put(position: LocalPosition) = value.change { stored ->
        StoredPositions(stored.positions.filterNot { it.fileId == position.fileId } + position.stored())
    }
}
