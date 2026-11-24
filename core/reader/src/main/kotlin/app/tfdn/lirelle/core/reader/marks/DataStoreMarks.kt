package app.tfdn.lirelle.core.reader.marks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.reader.store.JsonValue
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The marks store and the outbox in one value, under one key: a change is shown and queued in one
 * write ([record]), and a refresh decides what to keep from the queue as it is at that write. The
 * outbox's side is [StateOutbox], over the same value.
 *
 * The key is `marks2`: the first format, kept under `marks` with server items of both kinds under
 * `s<serverId>`, is never read; what it held is fetched again.
 *
 * @param wall dates the marks made here.
 * @param reading where the stored value is decoded and a book's marks picked from it, away from
 * the collector's thread.
 */
class DataStoreMarks private constructor(
    private val state: JsonValue<StoredMarksState>,
    private val wall: Clock,
    private val reading: CoroutineDispatcher,
) : MarksStore, MarkOutbox by StateOutbox(state, wall) {

    constructor(
        dataStore: DataStore<Preferences>,
        wall: Clock,
        reading: CoroutineDispatcher = Dispatchers.Default,
    ) : this(JsonValue(dataStore, "marks2", StoredMarksState.serializer(), StoredMarksState()), wall, reading)

    override fun book(bookId: BookId): Flow<BookMarks> =
        state.flow.map { it.book(bookId).shown() }.distinctUntilChanged().flowOn(reading)

    override suspend fun replaceFromServer(
        bookId: BookId,
        bookmarks: List<ServerBookmark>,
        highlights: List<ServerHighlight>,
        fileId: FileId?,
    ) = state.change { marks ->
        marks.withBook(bookId) { it.fromServer(bookmarks, highlights, marks.queued(bookId), fileId) }.trimmed()
    }

    override suspend fun held(bookId: BookId): HeldBook = state.read().book(bookId).held()

    override suspend fun apply(change: MarkChange) {
        val now = wall.instant()
        state.change { it.applied(change, now) }
    }

    override suspend fun bindServerId(bookId: BookId, id: MarkId, serverId: ServerMarkId) =
        state.change { marks -> marks.withBook(bookId) { it.bound(id.value, serverId, marks.queued(bookId)) } }
}
