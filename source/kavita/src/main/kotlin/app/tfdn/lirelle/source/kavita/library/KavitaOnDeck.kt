package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The on-deck series and their continue points, shared by Continue and Up next, which Home asks together: what
 * one row loaded serves the other row once, if it asks less than [SharedFor] milliseconds later, and is then let
 * go. So each refresh makes one on-deck call, and the next refresh asks again. A "See all" screen keeps its own,
 * being longer, and loads without waiting for Home's rows, though both share Home's limit on calls at once. Asked
 * while the other row loads them, they are waited for, never asked twice; a failure is not kept. Made with a
 * profile's library, they never serve another profile.
 * [clock] tells the time in milliseconds, from any start.
 */
internal class KavitaOnDeck(private val clock: () -> Long) {

    private val loading = HomeRowExtent.entries.associateWith { Mutex() }
    private val kept = ConcurrentHashMap<HomeRowExtent, Kept>()

    /**
     * The series for [kind]'s row as long as [extent] asks: those the other row loaded less than [SharedFor] ago,
     * else those [load] gives, kept for the other row.
     */
    suspend fun points(kind: HomeRowKind, extent: HomeRowExtent, load: suspend () -> SeriesPoints): SeriesPoints =
        loading.getValue(extent).withLock {
            val shared = kept.remove(extent)?.takeIf { it.loadedBy != kind && clock() - it.at < SharedFor }
            shared?.points ?: load().also { kept[extent] = Kept(clock(), it, kind) }
        }

    /** The series [loadedBy]'s row loaded [at] that time. */
    private data class Kept(val at: Long, val points: SeriesPoints, val loadedBy: HomeRowKind)
}

/** The time in milliseconds from a start of its own, which only moves forward. */
internal fun monotonicMillis(): Long = System.nanoTime() / NanosPerMilli

/** How long the on-deck series one row loaded may serve the other. */
private const val SharedFor = 10_000L

private const val NanosPerMilli = 1_000_000L
