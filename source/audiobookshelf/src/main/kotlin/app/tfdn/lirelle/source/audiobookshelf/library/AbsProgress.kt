package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaProgressDto
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The account's reading of one book.
 *
 * @property percent the furthest of the audio's and the ebook's progress, 0 to 100; null when neither has moved.
 * @property lastUpdate when the progress last moved; [startedAt] when the reading began.
 */
internal data class AbsReading(
    val state: ReadState,
    val percent: Int?,
    val lastUpdate: Instant?,
    val startedAt: Instant?,
)

/** The account's readings of its books, by item id. A book with no record of its own is unread. */
internal class AbsReadings private constructor(private val byItem: Map<String, AbsReading>) {

    /** How many books the account has a record of. */
    val size: Int get() = byItem.size

    /** The reading of the item [itemId]; null when the account has none. */
    fun of(itemId: String): AbsReading? = byItem[itemId]

    /** The read state of the item [itemId]: unread without a record. */
    fun state(itemId: String): ReadState = of(itemId)?.state ?: ReadState.Unread

    companion object {
        /** No reading at all. */
        val None = AbsReadings(emptyMap())

        /**
         * The readings the account's [records] say, as the server's own filters and shelves tell them: a finished
         * record is a book read; one not finished whose audio or ebook has moved a book being read (`in-progress`,
         * Continue); one not finished at nothing yet a book not started (`not-started`), unread. A podcast
         * episode's record ([AbsMediaProgressDto.episodeId] set) is no book's.
         */
        fun of(records: List<AbsMediaProgressDto>): AbsReadings = AbsReadings(
            records.filter { it.episodeId == null }.associate { it.libraryItemId to it.reading() },
        )
    }
}

/**
 * The account's progress, all of it in one answer ([read], `GET api/me`), merged into every list. What was read is
 * kept [Freshness] long by the clock [now], so that one screen's load, which asks it for each of its lists, makes
 * one call; asked again later, it is read again. Asked by several at once, it is read once. A read that fails is
 * thrown to whoever asked, and nothing is kept of it.
 */
internal class AbsProgress(
    private val read: suspend () -> List<AbsMediaProgressDto>,
    private val now: () -> Instant,
) {
    private val lock = Mutex()
    private var known: AbsReadings? = null
    private var readAt: Instant = Instant.MIN

    /** The account's readings, as read less than [Freshness] ago, or now. */
    suspend fun readings(): AbsReadings = lock.withLock {
        val asked = now()
        known?.takeIf { Duration.between(readAt, asked) <= Freshness }
            ?: AbsReadings.of(read()).also {
                known = it
                readAt = asked
            }
    }
}

/** How long the account's progress, once read, stands for what the server holds. */
private val Freshness: Duration = Duration.ofSeconds(10)

private const val FullPercent = 100

private fun AbsMediaProgressDto.reading(): AbsReading {
    val furthest = maxOf(progress ?: 0.0, ebookProgress ?: 0.0)
    val moved = (currentTime ?: 0.0) > 0.0 || furthest > 0.0
    return AbsReading(
        state = when {
            isFinished -> ReadState.Read
            moved -> ReadState.Reading
            else -> ReadState.Unread
        },
        percent = (furthest * FullPercent).toInt().coerceIn(0, FullPercent).takeIf { it > 0 },
        lastUpdate = lastUpdate?.let(Instant::ofEpochMilli),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
    )
}
