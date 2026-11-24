package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.WrittenPlace
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import java.time.Instant

/** [NarrationStore] kept in memory. */
internal class InMemoryNarrations : NarrationStore {
    private val narrations = mutableMapOf<FileId, LocalNarration>()

    override suspend fun get(fileId: FileId): LocalNarration? = narrations[fileId]

    override suspend fun put(n: LocalNarration) {
        narrations[n.fileId] = n
    }

    /** As on a device that has not kept any yet. */
    fun clear() = narrations.clear()
}

/** [PlaybackStore] kept in memory: the positions only, what the progress reads. */
internal class InMemoryPlaybacks : PlaybackStore {
    private val positions = mutableMapOf<BookId, LocalPlayback>()

    override suspend fun get(bookId: BookId): LocalPlayback? = positions[bookId]

    override suspend fun put(position: LocalPlayback) {
        positions[position.bookId] = position
    }

    override suspend fun knownVersion(bookId: BookId): String? = null

    override suspend fun setKnownVersion(bookId: BookId, version: String) = Unit

    override suspend fun lastWritten(bookId: BookId): Instant? = null

    override suspend fun setLastWritten(bookId: BookId, at: Instant, place: WrittenPlace?) = Unit

    override suspend fun writtenPlaces(bookId: BookId): List<WrittenPlace> = emptyList()
}

/** [PlaybackOutbox] kept in memory: only the latest of each book. */
internal class InMemoryPlaybackQueue : PlaybackOutbox {
    private var positions = emptyList<LocalPlayback>()

    override suspend fun enqueue(p: LocalPlayback) {
        positions = positions.filterNot { it.bookId == p.bookId } + p
    }

    override suspend fun pending(): List<LocalPlayback> = positions

    override suspend fun markSent(p: LocalPlayback) {
        positions = positions - p
    }

    override suspend fun replace(old: LocalPlayback, new: LocalPlayback) {
        positions = positions.map { if (it == old) new else it }
    }
}
