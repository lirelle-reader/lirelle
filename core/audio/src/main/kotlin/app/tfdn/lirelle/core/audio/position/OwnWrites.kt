package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import java.time.Instant

/** How many of its own writes per book the flusher follows, to send what comes after them on top. */
private const val OwnWritesRemembered = 8

/**
 * What the [PlaybackFlusher] remembers of this device's own writes, per book; forgotten when the profile
 * closes. Not thread-safe: only the one flush running uses it.
 */
internal class OwnWrites {
    /**
     * Per book, the version each of our last writes was made over (null: none, it created the state), and the one
     * it got.
     */
    private val chains = mutableMapOf<BookId, LinkedHashMap<String?, String>>()
    private val acknowledged = mutableMapOf<BookId, Instant>()

    /** Per book, the latest of our writes whose outcome is unknown: the server may have stored it. */
    private val uncertain = mutableMapOf<BookId, Instant>()
    private val manifestRounds = mutableMapOf<BookId, Int>()

    /** The version [position] goes on top of: past our own writes made since its base. */
    fun baseOf(position: LocalPlayback): String? {
        val chain = chains[position.bookId] ?: return position.baseVersion
        var base = position.baseVersion
        repeat(chain.size) { base = chain[base] ?: return base }
        return base
    }

    /** The server took [position], written over [base], as [version]. */
    fun saved(position: LocalPlayback, base: String?, version: String) {
        val chain = chains.getOrPut(position.bookId) { LinkedHashMap() }
        chain[base] = version
        if (chain.size > OwnWritesRemembered) chain.remove(chain.keys.first())
        acknowledged.keepLatest(position)
        manifestRounds -= position.bookId
    }

    /** [position] was sent, but no answer says whether the server stored it. */
    fun uncertain(position: LocalPlayback) = uncertain.keepLatest(position)

    /**
     * The time of the latest of our writes the server acknowledged, or may have stored, for
     * [bookId], as it was captured.
     */
    fun lastWritten(bookId: BookId): Instant? =
        listOfNotNull(acknowledged[bookId], uncertain[bookId]).maxOrNull()

    /** The book's state is gone from the server: none of our versions leads anywhere any more. */
    fun forget(bookId: BookId) {
        chains -= bookId
    }

    /** How many times [bookId]'s position was placed again in new files since its last saved write. */
    fun placedAgain(bookId: BookId): Int = manifestRounds[bookId] ?: 0

    fun countPlacedAgain(bookId: BookId) {
        manifestRounds[bookId] = placedAgain(bookId) + 1
    }

    private fun MutableMap<BookId, Instant>.keepLatest(position: LocalPlayback) {
        val last = this[position.bookId]
        if (last == null || position.at.isAfter(last)) this[position.bookId] = position.at
    }
}
