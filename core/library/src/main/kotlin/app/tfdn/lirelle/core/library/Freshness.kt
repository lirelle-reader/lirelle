package app.tfdn.lirelle.core.library

private const val DefaultMaxAgeMillis = 120_000L

/** A clock that only moves forward, whatever the wall clock does. */
fun interface MonotonicClock {
    fun nowMillis(): Long
}

/** Tells whether what a screen shows is old enough to be loaded again when the screen comes back. */
class Freshness(private val clock: MonotonicClock, private val maxAgeMillis: Long = DefaultMaxAgeMillis) {
    private var loadedAt: Long? = null

    fun markLoaded() {
        loadedAt = clock.nowMillis()
    }

    /** False while nothing was loaded: the first load is already on its way. */
    fun isStale(): Boolean = loadedAt?.let { clock.nowMillis() - it > maxAgeMillis } ?: false
}
