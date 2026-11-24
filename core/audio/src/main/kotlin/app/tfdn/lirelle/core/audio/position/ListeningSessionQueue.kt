package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import javax.inject.Inject

/** A listening session waiting to be declared, for the file that was heard. */
data class QueuedSession(val fileId: FileId, val report: ReadingSessionReport)

/**
 * The listening sessions still to reach the server, kept across restarts. A session is declared again as it
 * goes on (same id, longer): only its latest report waits.
 */
interface ListeningSessionQueue {
    /** Replaces what waits with the same session id. */
    suspend fun enqueue(fileId: FileId, report: ReadingSessionReport)

    /** What waits, oldest first. */
    suspend fun pending(): List<QueuedSession>

    /** Removes [session] if it is still exactly what waits: a longer report enqueued meanwhile stays. */
    suspend fun markSent(session: QueuedSession)
}

/**
 * This queue for a profile whose source can do [capabilities] (spec 2c): without reading sessions, no listening
 * session is queued, so none is declared.
 */
fun ListeningSessionQueue.forSource(capabilities: SourceCapabilities): ListeningSessionQueue =
    if (capabilities.readingSessions) this else WithoutListeningSessions(this)

private class WithoutListeningSessions(queue: ListeningSessionQueue) : ListeningSessionQueue by queue {
    override suspend fun enqueue(fileId: FileId, report: ReadingSessionReport) = Unit
}

class DataStoreListeningSessionQueue(dataStore: DataStore<Preferences>) : ListeningSessionQueue {
    private val value =
        JsonValue(dataStore, "listening_sessions", StoredSessionQueue.serializer(), StoredSessionQueue())

    override suspend fun enqueue(fileId: FileId, report: ReadingSessionReport) {
        val stored = StoredSession.of(QueuedSession(fileId, report))
        value.change { queue ->
            queue.copy(sessions = queue.sessions.filterNot { it.sessionId == report.sessionId } + stored)
        }
    }

    override suspend fun pending(): List<QueuedSession> = value.read().sessions.map { it.queued() }

    override suspend fun markSent(session: QueuedSession) =
        value.change { queue -> queue.copy(sessions = queue.sessions - StoredSession.of(session)) }
}

/** Queues a listening session and has it sent with the positions, after them. */
class ListeningSessionSender @Inject constructor(
    private val queue: ListeningSessionQueue,
    private val flusher: PlaybackFlusher,
) {
    suspend fun send(fileId: FileId, report: ReadingSessionReport) {
        queue.enqueue(fileId, report)
        flusher.kick()
    }
}

/**
 * Declares what the [ListeningSessionQueue] holds, for the [PlaybackFlusher], with its rules for failures:
 * no network or a failing server waits for the next round, a book or file the server no longer has is given
 * up, an ended session stops the round, anything else is given up after [RetryPolicy.unclearAttempts].
 */
internal class SessionSender(
    private val queue: ListeningSessionQueue,
    private val repository: AudioRepository,
    policy: RetryPolicy,
) {
    private val failures = UnclearFailures<String>(policy)

    /** True when something is left worth another round. */
    suspend fun sendAll(): Boolean {
        var again = false
        for (session in queue.pending()) {
            val result = repository.saveListeningSession(session.fileId, session.report)
            val error = (result as? ApiResult.Failure)?.error
            // Sent, or given up.
            when (error?.let { failures.verdict(session.report.sessionId, it) }) {
                null, AfterFailure.GiveUp -> queue.markSent(session)
                AfterFailure.Retry -> again = true
                AfterFailure.Stop -> return false
            }
        }
        return again
    }
}
