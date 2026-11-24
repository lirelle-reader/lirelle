package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue

/**
 * The positions still to reach the server, kept across restarts: only the latest of each book.
 * Safe to use from several coroutines at once.
 */
interface PlaybackOutbox {
    /** Replaces what waits for the same book. */
    suspend fun enqueue(p: LocalPlayback)

    /** What waits, oldest first. */
    suspend fun pending(): List<LocalPlayback>

    /** Removes [p] if it is still exactly what waits: a newer position enqueued meanwhile stays. */
    suspend fun markSent(p: LocalPlayback)

    /** Puts [new] in the place of [old] if [old] is still exactly what waits; nothing otherwise. */
    suspend fun replace(old: LocalPlayback, new: LocalPlayback)
}

class DataStorePlaybackOutbox(dataStore: DataStore<Preferences>) : PlaybackOutbox {
    private val value =
        JsonValue(dataStore, "playback_outbox", StoredPlaybackOutbox.serializer(), StoredPlaybackOutbox())

    override suspend fun enqueue(p: LocalPlayback) = value.change { box ->
        box.copy(positions = box.positions.filterNot { it.bookId == p.bookId } + p.stored())
    }

    override suspend fun pending(): List<LocalPlayback> = value.read().positions.map { it.local() }

    override suspend fun markSent(p: LocalPlayback) =
        value.change { box -> box.copy(positions = box.positions - p.stored()) }

    override suspend fun replace(old: LocalPlayback, new: LocalPlayback) = value.change { box ->
        val was = old.stored()
        box.copy(positions = box.positions.map { if (it == was) new.stored() else it })
    }
}
