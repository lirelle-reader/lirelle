package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.sync.Semaphore

/** How many covers are asked of the server at once: each one holds a thread of those the system calls the app on. */
private const val FetchesAtOnce = 4

/** How long a cover that could not be had is not asked again: a car asks for the same covers over and over. */
private const val FailureMemoryMillis = 60_000L

/**
 * Bounds what the car's covers cost when the server is slow or cannot be reached: each request of a cover waits
 * on one of the few threads the system calls the app on, which the session needs too. At most [atOnce] covers
 * are asked of the server together, and a cover that could not be had is not asked again for a minute; a
 * request turned away has no cover, and the car shows its placeholder. One for the profile, as its requests are.
 */
@ProfileScoped
class CarCoverFetches(private val clock: MonotonicClock, atOnce: Int) {

    @Inject
    constructor(clock: MonotonicClock) : this(clock, FetchesAtOnce)

    private val slots = Semaphore(atOnce)

    /** When the cover of a book last could not be had, by [clock]. */
    private val failedAt = ConcurrentHashMap<BookId, Long>()

    /**
     * What [fetch] finds for [bookId]; null without asking it when it found nothing a moment ago, or when as
     * many as may are being fetched. A fetch its caller gave up on counts as one that found nothing.
     */
    suspend fun <T : Any> attempt(bookId: BookId, fetch: suspend () -> T?): T? {
        if (failedLately(bookId) || !slots.tryAcquire()) return null
        var found: T? = null
        try {
            found = fetch()
            return found
        } finally {
            slots.release()
            if (found == null) failed(bookId) else failedAt.remove(bookId)
        }
    }

    private fun failedLately(bookId: BookId): Boolean =
        failedAt[bookId]?.let { clock.nowMillis() - it < FailureMemoryMillis } == true

    private fun failed(bookId: BookId) {
        val now = clock.nowMillis()
        // Those of long ago are of no use: what is remembered stays as short as what fails now.
        failedAt.values.removeIf { now - it >= FailureMemoryMillis }
        failedAt[bookId] = now
    }
}
