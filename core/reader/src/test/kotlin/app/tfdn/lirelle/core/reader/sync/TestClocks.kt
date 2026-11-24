package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.library.MonotonicClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration

/** A monotonic clock and a wall clock moved by hand, together. */
class TestClocks(private val start: Instant = Instant.parse("2026-11-14T10:00:00Z")) {
    private var elapsedMillis = 0L

    val monotonic = MonotonicClock { BootMillis + elapsedMillis }

    val wall: Clock = object : Clock() {
        override fun instant(): Instant = start.plusMillis(elapsedMillis)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    val now: Instant get() = wall.instant()

    fun advance(by: Duration) {
        elapsedMillis += by.inWholeMilliseconds
    }

    private companion object {
        const val BootMillis = 5_000_000L
    }
}
