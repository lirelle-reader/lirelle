package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import java.time.Instant

/** The content version of the samples' audiobook: 64 hex digits, as BookOrbit gives them. */
val SampleContent = "a".repeat(64)

/** Book 12, the book of the samples. */
val SampleBook = BookId("12")

/** Another book than [SampleBook]. */
val OtherBook = BookId("13")

/** A position of book 12, 90 s into its first file, made at [at]. */
fun samplePlayback(at: Instant = Instant.parse("2026-11-15T10:00:00Z"), positionMs: Long = 90_000) = LocalPlayback(
    bookId = SampleBook,
    assetId = "aud_one",
    positionMs = positionMs,
    bookTimeMs = positionMs,
    at = at,
    operationId = "op-$positionMs",
    baseVersion = null,
    contentVersion = SampleContent,
)

/** [PlaybackOutbox] kept in memory, with the rules of the real one. */
class InMemoryPlaybackOutbox : PlaybackOutbox {
    /** What is stored now. */
    var positions: List<LocalPlayback> = emptyList()
        private set

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

/** [PlaybackStore] kept in memory. */
class InMemoryPlaybackStore : PlaybackStore {
    private val positions = mutableMapOf<BookId, LocalPlayback>()
    private val versions = mutableMapOf<BookId, String>()
    private val written = mutableMapOf<BookId, Instant>()
    private val places = mutableMapOf<BookId, List<WrittenPlace>>()

    override suspend fun get(bookId: BookId): LocalPlayback? = positions[bookId]

    override suspend fun put(position: LocalPlayback) {
        positions[position.bookId] = position
    }

    override suspend fun knownVersion(bookId: BookId): String? = versions[bookId]

    override suspend fun setKnownVersion(bookId: BookId, version: String) {
        versions[bookId] = version
    }

    override suspend fun lastWritten(bookId: BookId): Instant? = written[bookId]

    override suspend fun setLastWritten(bookId: BookId, at: Instant, place: WrittenPlace?) {
        if (written[bookId]?.isBefore(at) != false) written[bookId] = at
        if (place != null) places[bookId] = (places[bookId].orEmpty() - place + place).takeLast(WrittenPlacesKept)
    }

    override suspend fun writtenPlaces(bookId: BookId): List<WrittenPlace> = places[bookId].orEmpty()
}
