package app.tfdn.lirelle.core.audio.bookmarks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val Book = BookId("5")
val Made: Instant = Instant.parse("2026-11-15T10:00:00Z")

fun bookmark(
    id: String,
    positionMs: Long = 600_000,
    title: String = "Bookmark $id",
    note: String? = null,
    createdAt: Instant? = Made,
) = AudioBookmark(id, Book, positionMs, null, title, note, createdAt, createdAt)

/** A preference store in memory: it never leaves the caller's dispatcher, so virtual time drives it. */
class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}
