package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxContent
import app.tfdn.lirelle.core.reader.sync.PendingSession

/**
 * [Outbox] kept in memory, with the rules of the real one. [log] lists every enqueue, in order, as
 * "position 12", "narration 12" or "session 12 <id>", whatever was sent or replaced since.
 */
class InMemoryOutbox : Outbox {
    private var content = OutboxContent()

    val log = mutableListOf<String>()

    /** What is stored now. */
    val positions: List<LocalPosition> get() = content.positions
    val sessions: List<PendingSession> get() = content.sessions
    val narrations: List<LocalNarration> get() = content.narrations

    override suspend fun enqueuePosition(position: LocalPosition) {
        log += "position ${position.fileId}"
        content = content.copy(positions = content.positions.filterNot { it.fileId == position.fileId } + position)
    }

    override suspend fun enqueueNarration(n: LocalNarration) {
        log += "narration ${n.fileId}"
        content = content.copy(narrations = content.narrations.filterNot { it.fileId == n.fileId } + n)
    }

    override suspend fun enqueueSession(fileId: FileId, session: ReadingSessionReport) {
        log += "session $fileId ${session.sessionId}"
        val kept = content.sessions.filterNot { it.session.sessionId == session.sessionId }
        content = content.copy(sessions = kept + PendingSession(fileId, session))
    }

    override suspend fun pending(): OutboxContent = content

    override suspend fun markSent(position: LocalPosition) {
        content = content.copy(positions = content.positions - position)
    }

    override suspend fun markSent(n: LocalNarration) {
        content = content.copy(narrations = content.narrations - n)
    }

    override suspend fun markSent(session: PendingSession) {
        content = content.copy(sessions = content.sessions - session)
    }
}
