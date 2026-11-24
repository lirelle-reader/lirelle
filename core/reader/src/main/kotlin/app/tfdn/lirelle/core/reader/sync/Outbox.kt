package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.store.JsonValue

/** A reading session still to be sent, with the file it was read in. */
data class PendingSession(val fileId: FileId, val session: ReadingSessionReport)

/**
 * What still has to reach the server, oldest first.
 *
 * @property narrations the narrations of read-along books, kept apart from the text [positions] of
 * the same files.
 */
data class OutboxContent(
    val positions: List<LocalPosition> = emptyList(),
    val sessions: List<PendingSession> = emptyList(),
    val narrations: List<LocalNarration> = emptyList(),
) {
    val isEmpty: Boolean get() = positions.isEmpty() && sessions.isEmpty() && narrations.isEmpty()
}

/**
 * What still has to reach the server, kept across restarts. Safe to use from several coroutines
 * at once.
 */
interface Outbox {
    /** Only the latest position of a file is kept. */
    suspend fun enqueuePosition(position: LocalPosition)

    /** Only the latest narration of a file is kept, apart from its text position. */
    suspend fun enqueueNarration(n: LocalNarration)

    /** A session with an id already there replaces it: a checkpoint, then the final report. */
    suspend fun enqueueSession(fileId: FileId, session: ReadingSessionReport)

    /** What waits, oldest first. */
    suspend fun pending(): OutboxContent

    /**
     * Removes [position] if it is still exactly what is stored. A newer position enqueued for the
     * same file while this one was being sent stays.
     */
    suspend fun markSent(position: LocalPosition)

    /** Removes [n] if it is still exactly what is stored, like a position; the text position stays. */
    suspend fun markSent(n: LocalNarration)

    /** Removes [session] if it is still exactly what is stored; a later report with the same id stays. */
    suspend fun markSent(session: PendingSession)
}

/**
 * This outbox for a profile whose source can do [capabilities] (spec 2c): without reading sessions, a session is
 * never queued, so never sent; the positions and narrations are queued as ever.
 */
fun Outbox.forSource(capabilities: SourceCapabilities): Outbox =
    if (capabilities.readingSessions) this else WithoutSessions(this)

private class WithoutSessions(outbox: Outbox) : Outbox by outbox {
    override suspend fun enqueueSession(fileId: FileId, session: ReadingSessionReport) = Unit
}

class DataStoreOutbox(dataStore: DataStore<Preferences>) : Outbox {
    private val value = JsonValue(dataStore, "outbox", StoredOutbox.serializer(), StoredOutbox())

    override suspend fun enqueuePosition(position: LocalPosition) = value.change { box ->
        box.copy(positions = box.positions.filterNot { it.fileId == position.fileId } + position.stored())
    }

    override suspend fun enqueueNarration(n: LocalNarration) = value.change { box ->
        box.copy(narrations = box.narrations.filterNot { it.fileId == n.fileId } + n.stored())
    }

    override suspend fun enqueueSession(fileId: FileId, session: ReadingSessionReport) = value.change { box ->
        val added = PendingSession(fileId, session).stored()
        box.copy(sessions = box.sessions.filterNot { it.sessionId == session.sessionId } + added)
    }

    override suspend fun pending(): OutboxContent {
        val box = value.read()
        return OutboxContent(
            positions = box.positions.map { it.position() },
            sessions = box.sessions.map { it.pending() },
            narrations = box.narrations.map { it.narration() },
        )
    }

    override suspend fun markSent(position: LocalPosition) =
        value.change { box -> box.copy(positions = box.positions - position.stored()) }

    override suspend fun markSent(n: LocalNarration) =
        value.change { box -> box.copy(narrations = box.narrations - n.stored()) }

    override suspend fun markSent(session: PendingSession) =
        value.change { box -> box.copy(sessions = box.sessions - session.stored()) }
}
