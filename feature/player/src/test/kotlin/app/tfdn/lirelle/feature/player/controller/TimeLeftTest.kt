package app.tfdn.lirelle.feature.player.controller

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every time left the player shows: listening time at the speed. */
class TimeLeftTest {

    @Test
    fun `at 1x the time left is the book time left`() {
        assertThat(timeLeft(totalMs = 40_000, bookTimeMs = 10_000, speed = 1f)).isEqualTo(30_000)
    }

    @Test
    fun `faster, less listening time is left, slower, more`() {
        assertThat(timeLeft(40_000, 10_000, 2f)).isEqualTo(15_000)
        assertThat(timeLeft(40_000, 10_000, 0.5f)).isEqualTo(60_000)
    }

    @Test
    fun `the result is rounded to the millisecond`() {
        assertThat(timeLeft(10_000, 4_000, 1.25f)).isEqualTo(4_800)
        assertThat(timeLeft(10_000, 0, 3f)).isEqualTo(3_333)
    }

    @Test
    fun `nothing is left past the end, and a speed of zero leaves the book time`() {
        assertThat(timeLeft(40_000, 45_000, 2f)).isEqualTo(0)
        assertThat(timeLeft(40_000, 10_000, 0f)).isEqualTo(30_000)
    }
}
