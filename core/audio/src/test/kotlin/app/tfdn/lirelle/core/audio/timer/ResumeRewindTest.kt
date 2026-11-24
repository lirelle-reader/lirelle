package app.tfdn.lirelle.core.audio.timer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumeRewindTest {

    private val minute = 60_000L

    @Test
    fun `a short pause rewinds 2 seconds, up to 5 minutes`() {
        assertThat(ResumeRewind.rewindMs(0, true)).isEqualTo(2_000L)
        assertThat(ResumeRewind.rewindMs(5 * minute, true)).isEqualTo(2_000L)
    }

    @Test
    fun `from 5 minutes to an hour it grows linearly from 2 to 10 seconds`() {
        assertThat(ResumeRewind.rewindMs(5 * minute + 1, true)).isAtLeast(2_000L)
        // Half way: 32.5 minutes.
        assertThat(ResumeRewind.rewindMs(5 * minute + 55 * minute / 2, true)).isEqualTo(6_000L)
        assertThat(ResumeRewind.rewindMs(59 * minute, true)).isLessThan(10_000L)
    }

    @Test
    fun `an hour or more rewinds 10 seconds`() {
        assertThat(ResumeRewind.rewindMs(60 * minute, true)).isEqualTo(10_000L)
        assertThat(ResumeRewind.rewindMs(48 * 60 * minute, true)).isEqualTo(10_000L)
    }

    @Test
    fun `nothing when the setting is off`() {
        assertThat(ResumeRewind.rewindMs(2 * 60 * minute, false)).isEqualTo(0L)
    }

    @Test
    fun `the rewind is never longer than 10 seconds and never decreases`() {
        var last = 0L
        for (m in 0..120) {
            val r = ResumeRewind.rewindMs(m * minute, true)
            assertThat(r).isAtLeast(last)
            assertThat(r).isAtMost(10_000L)
            last = r
        }
    }

    @Test
    fun `resuming never goes before the start of the book`() {
        assertThat(ResumeRewind.resumeAt(1_000L, 2 * 60 * minute, true)).isEqualTo(0L)
        assertThat(ResumeRewind.resumeAt(100_000L, 2 * 60 * minute, true)).isEqualTo(90_000L)
        assertThat(ResumeRewind.resumeAt(100_000L, 2 * 60 * minute, false)).isEqualTo(100_000L)
    }
}
