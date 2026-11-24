package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import app.tfdn.lirelle.core.ids.BookId
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Where the user is in a book, as this device knows it.
 *
 * @property positionMs inside [assetId].
 * @property bookTimeMs the same place on the whole book's timeline: what places it again when the
 * book's files change.
 * @property at when the user was there, by the device's clock (the write's `capturedAt`).
 * @property operationId the write's id: each new position has its own ([PositionWriter] gives it),
 * a retry of the same position keeps it, so the server never applies it twice.
 * @property baseVersion the version of the book's state the write is made over: the last one the server gave this
 * device for the book, or the one the user answered; null when none is known, which creates it. [PositionWriter]
 * fills it in.
 * @property contentVersion the content version of the manifest [assetId] comes from.
 */
data class LocalPlayback(
    val bookId: BookId,
    val assetId: String,
    val positionMs: Long,
    val bookTimeMs: Long,
    val at: Instant,
    val operationId: String = newOperationId(),
    val baseVersion: String? = null,
    val contentVersion: String,
)

internal fun newOperationId(): String = UUID.randomUUID().toString()

/** A place of a book this device wrote: a server state there is this device's own, whatever its date says. */
@Serializable
data class WrittenPlace(val assetId: String, val positionMs: Long)

/** How many of the places last written of each book are kept. */
internal const val WrittenPlacesKept = 8

/**
 * The last position of each book, the version of its state this device knows and when the
 * last write the server took (or may have taken) was captured, kept across restarts so that a book
 * reopens at the right place without the network, and a position written elsewhere since this device's
 * last write is still told apart after a restart.
 */
interface PlaybackStore {
    suspend fun get(bookId: BookId): LocalPlayback?

    suspend fun put(position: LocalPlayback)

    /**
     * The version of the book's state this device knows: the one its last write got, or the one it read and
     * followed or answered. A state in another version was written elsewhere. Null when the server never gave
     * one: the next write creates the book's state. A revision stored as a number by the versions before 2b
     * reads back as its text.
     */
    suspend fun knownVersion(bookId: BookId): String?

    /**
     * The positions made from now on are written over [version]. Set it when the server's state was read and
     * followed or dismissed ("Ignore"): the next write then wins.
     */
    suspend fun setKnownVersion(bookId: BookId, version: String)

    /**
     * When the last write of [bookId] the server took, or may have taken (sent, no answer), was captured;
     * null when none was. A state captured later on the server was written elsewhere.
     */
    suspend fun lastWritten(bookId: BookId): Instant?

    /**
     * A write captured [at], at [place], was taken by the server, or may have been: its time kept when it is
     * the latest, its place as one of the last [WrittenPlacesKept].
     */
    suspend fun setLastWritten(bookId: BookId, at: Instant, place: WrittenPlace? = null)

    /** The places of the last writes of [bookId] the server took or may have taken, the latest last. */
    suspend fun writtenPlaces(bookId: BookId): List<WrittenPlace>
}

class DataStorePlaybackStore(dataStore: DataStore<Preferences>) : PlaybackStore {
    private val value = JsonValue(dataStore, "playback", StoredListening.serializer(), StoredListening())

    override suspend fun get(bookId: BookId): LocalPlayback? =
        value.read().positions.lastOrNull { it.bookId == bookId }?.local()

    override suspend fun put(position: LocalPlayback) = value.change { stored ->
        stored.copy(positions = stored.positions.filterNot { it.bookId == position.bookId } + position.stored())
    }

    override suspend fun knownVersion(bookId: BookId): String? = value.read().versions[bookId]

    override suspend fun setKnownVersion(bookId: BookId, version: String) = value.change { stored ->
        stored.copy(versions = stored.versions + (bookId to version))
    }

    override suspend fun lastWritten(bookId: BookId): Instant? = value.read().written[bookId]

    override suspend fun setLastWritten(bookId: BookId, at: Instant, place: WrittenPlace?) = value.change { stored ->
        val last = stored.written[bookId]
        val written = if (last != null && !at.isAfter(last)) stored.written else stored.written + (bookId to at)
        val places = place?.let { (stored.places[bookId].orEmpty() - it + it).takeLast(WrittenPlacesKept) }
        stored.copy(written = written, places = places?.let { stored.places + (bookId to it) } ?: stored.places)
    }

    override suspend fun writtenPlaces(bookId: BookId): List<WrittenPlace> = value.read().places[bookId].orEmpty()
}
