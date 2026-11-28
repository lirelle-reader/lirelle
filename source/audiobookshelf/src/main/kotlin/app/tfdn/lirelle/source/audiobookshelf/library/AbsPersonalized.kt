package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibraryDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsShelfDto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A book [library] and its Home [shelves] (`GET api/libraries/:id/personalized`). */
internal data class LibraryShelves(val library: AbsLibraryDto, val shelves: List<AbsShelfDto>)

/**
 * The Home shelves of each book library, shared by Up next and Recently added, which Home asks together: what one
 * row loaded serves the other row once, if it asks less than [SharedFor] milliseconds later, and is then let go. So
 * each refresh asks each library once, and the next refresh asks again. A "See all" screen keeps its own, being
 * longer. Asked while the other row loads them, they are waited for, never asked twice; a failure is not kept. Made
 * with a profile's library, they never serve another profile. [clock] tells the time in milliseconds, from any start.
 */
internal class AbsPersonalized(private val clock: () -> Long) {

    private val loading = HomeRowExtent.entries.associateWith { Mutex() }
    private val kept = ConcurrentHashMap<HomeRowExtent, Kept>()

    /**
     * The shelves for [kind]'s row as long as [extent] asks: those the other row loaded less than [SharedFor] ago,
     * else those [load] gives, kept for the other row.
     */
    suspend fun shelves(
        kind: HomeRowKind,
        extent: HomeRowExtent,
        load: suspend () -> List<LibraryShelves>,
    ): List<LibraryShelves> = loading.getValue(extent).withLock {
        val shared = kept.remove(extent)?.takeIf { it.loadedBy != kind && clock() - it.at < SharedFor }
        shared?.shelves ?: load().also { kept[extent] = Kept(clock(), it, kind) }
    }

    /** The shelves [loadedBy]'s row loaded [at] that time. */
    private data class Kept(val at: Long, val shelves: List<LibraryShelves>, val loadedBy: HomeRowKind)
}

/** The time in milliseconds from a start of its own, which only moves forward. */
internal fun monotonicMillis(): Long = System.nanoTime() / NanosPerMilli

/** How long the shelves one row loaded may serve the other. */
private const val SharedFor = 10_000L

private const val NanosPerMilli = 1_000_000L
