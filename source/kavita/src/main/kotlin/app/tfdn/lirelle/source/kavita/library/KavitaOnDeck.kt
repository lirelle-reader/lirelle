package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.HomeRowExtent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The on-deck series and their continue points, kept [SharedFor] milliseconds after they came, so that Continue and
 * Up next, which Home asks together, make one on-deck call between them. A "See all" screen keeps its own, being
 * longer. Asked while another row loads them, they are waited for, never asked twice; a failure is not kept. Made
 * with a profile's library, they never serve another profile. [clock] tells the time in milliseconds, from any
 * start.
 */
internal class KavitaOnDeck(private val clock: () -> Long) {

    private val loading = Mutex()
    private val kept = mutableMapOf<HomeRowExtent, Kept>()

    /** The series for [extent], as kept when they came less than [SharedFor] ago, else as [load] gives them. */
    suspend fun points(extent: HomeRowExtent, load: suspend () -> SeriesPoints): SeriesPoints = loading.withLock {
        val fresh = kept[extent]?.takeIf { clock() - it.at < SharedFor }
        fresh?.points ?: load().also { kept[extent] = Kept(clock(), it) }
    }

    private data class Kept(val at: Long, val points: SeriesPoints)
}

/** The time in milliseconds from a start of its own, which only moves forward. */
internal fun monotonicMillis(): Long = System.nanoTime() / NanosPerMilli

/** How long the on-deck series are shared between Home's rows. */
private const val SharedFor = 10_000L

private const val NanosPerMilli = 1_000_000L
