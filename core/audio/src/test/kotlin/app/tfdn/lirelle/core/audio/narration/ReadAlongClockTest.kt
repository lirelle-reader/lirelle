package app.tfdn.lirelle.core.audio.narration

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/** The server's narration clock (clip durations only) against the map's timeline (whole segments). */
class ReadAlongClockTest {

    private val m4b = ReadAlongFixtures.map("m4b")
    private val gap = ReadAlongFixtures.map("gap")
    private val mp3 = ReadAlongFixtures.map("mp3")
    private val after = ReadAlongFixtures.map("after")

    // One segment with a 1 s pause between its two clips.
    private val paused = synthetic(
        items = listOf(item(1, "s1", "a.mp3", 0, 4_000), item(1, "s2", "a.mp3", 5_000, 8_000)),
    )

    @Test
    fun `on gap-free clips the clock and the timeline go round within a millisecond`() {
        for (time in listOf(0L, 12_345L, 444_660L, 479_399L, 479_400L, 600_001L, 701_820L)) {
            val back = m4b.timeOfClock(m4b.clockSeconds(time))
            assertThat(abs(back - time)).isAtMost(1)
        }
        for (time in listOf(30_000L, 130_000L, 347_480L, 1_000_000L, 1_700_000L, 3_050_000L)) {
            assertThat(abs(mp3.timeOfClock(mp3.clockSeconds(time)) - time)).isAtMost(1)
        }
    }

    @Test
    fun `on gap-free clips the clock is the timeline, wherever the clips sit in their files`() {
        assertThat(m4b.clockSeconds(600_000)).isWithin(1e-9).of(600.0)
        assertThat(after.clockSeconds(2_865_120)).isWithin(1e-9).of(2_865.12)
        // Track 009's first segment starts 76.34 s into the file, track 015's 29.64 s in.
        assertThat(mp3.clockSeconds(46_920)).isWithin(1e-9).of(46.92)
        assertThat(mp3.clockSeconds(1_550_280 + 10_000)).isWithin(1e-9).of(1_560.28)
        // Section 24's only clip starts 4 268.92 s into track 006.
        assertThat(gap.clockSeconds(3_445_880 + 5_000)).isWithin(1e-9).of(3_450.88)
        assertThat(gap.timeOfClock(3_450.88)).isEqualTo(3_450_880)
    }

    @Test
    fun `where clip time and timeline differ, the clock stands still between clips`() {
        assertThat(paused.totalMs).isEqualTo(8_000)
        assertThat(paused.clockSeconds(3_000)).isWithin(1e-9).of(3.0)
        assertThat(paused.clockSeconds(4_500)).isWithin(1e-9).of(4.0)
        assertThat(paused.clockSeconds(6_000)).isWithin(1e-9).of(5.0)
        assertThat(paused.clockSeconds(8_000)).isWithin(1e-9).of(7.0)
        assertThat(paused.timeOfClock(5.0)).isEqualTo(6_000)
        assertThat(paused.timeOfClock(4.0)).isEqualTo(5_000)
    }

    @Test
    fun `the clock is clamped to the narration`() {
        assertThat(m4b.clockSeconds(-5)).isWithin(1e-9).of(0.0)
        assertThat(m4b.clockSeconds(9_999_999)).isWithin(1e-9).of(701.82)
        assertThat(m4b.timeOfClock(-1.0)).isEqualTo(0)
        assertThat(m4b.timeOfClock(10_000.0)).isEqualTo(701_820)
    }

    @Test
    fun `the percentage is the clock over the clock's total`() {
        assertThat(after.percentAt(0)).isWithin(1e-9).of(0.0)
        assertThat(after.percentAt(2_397_380)).isWithin(1e-9).of(50.0)
        assertThat(after.percentAt(after.totalMs)).isWithin(1e-9).of(100.0)
        assertThat(gap.percentAt(3_445_880)).isWithin(1e-9).of(3_445_880 * 100.0 / 3_764_940)
        assertThat(paused.percentAt(4_500)).isWithin(1e-9).of(4_000 * 100.0 / 7_000)
        assertThat(mp3.percentAt(mp3.totalMs)).isWithin(1e-9).of(100.0)
    }
}
