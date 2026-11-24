package app.tfdn.lirelle.core.audio.session

import app.tfdn.lirelle.core.library.MonotonicClock
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class ListeningSessionClockTest {

    private var elapsed = 0L
    private val start = Instant.parse("2026-11-14T10:00:00Z")
    private val monotonic = MonotonicClock { 5_000_000L + elapsed }
    private val wall = object : Clock() {
        override fun instant(): Instant = start.plusMillis(elapsed)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
    private val clock = ListeningSessionClock(monotonic, wall)

    private fun advance(seconds: Long) {
        elapsed += seconds * 1000
    }

    /** A tick at [percent] of a book of 1 000 s. */
    private fun play(percent: Double) = clock.onPlaying((percent * 10_000).toLong(), Total)

    /** Playing at speed 1 from [fromMs] for [seconds], a tick every half second, in a book of [totalMs]. */
    private fun listen(fromMs: Long, seconds: Int, totalMs: Long?) {
        repeat(seconds * 2 + 1) { step ->
            if (step > 0) elapsed += 500
            clock.onPlaying(fromMs + step * 500L, totalMs)
        }
    }

    @Test
    fun `a session counts the playing time and the progress`() {
        play(10.0)
        advance(30)
        play(10.5)
        advance(30)
        play(11.0)

        val report = clock.close()!!

        assertThat(report.activeSeconds).isEqualTo(60)
        assertThat(report.progressDelta).isEqualTo(1.0)
        assertThat(report.endPercent).isEqualTo(11.0)
        assertThat(report.startedAt).isEqualTo(start)
    }

    @Test
    fun `less than 10 seconds is not sent`() {
        play(10.0)
        advance(9)
        play(10.1)

        assertThat(clock.checkpoint()).isNull()
        assertThat(clock.close()).isNull()
    }

    @Test
    fun `time paused is not counted and a short pause goes on in the same session`() {
        play(10.0)
        advance(20)
        play(10.5)
        clock.onPaused()
        val id = clock.checkpoint()!!.sessionId
        advance(120)

        assertThat(play(10.5)).isNull()
        advance(20)
        play(11.0)

        val report = clock.close()!!
        assertThat(report.sessionId).isEqualTo(id)
        assertThat(report.activeSeconds).isEqualTo(40)
    }

    @Test
    fun `a pause of 5 minutes or more closes the session and the next playing starts another`() {
        play(10.0)
        advance(60)
        play(11.0)
        clock.onPaused()
        val first = clock.checkpoint()!!
        advance(300)

        val closed = play(11.0)

        assertThat(closed!!.sessionId).isEqualTo(first.sessionId)
        assertThat(closed.activeSeconds).isEqualTo(60)
        advance(30)
        play(11.5)
        val second = clock.close()!!
        assertThat(second.sessionId).isNotEqualTo(first.sessionId)
        assertThat(second.activeSeconds).isEqualTo(30)
    }

    @Test
    fun `a closed session that was too short returns nothing`() {
        play(10.0)
        advance(5)
        play(10.1)
        clock.onPaused()
        advance(600)

        assertThat(play(10.1)).isNull()
    }

    @Test
    fun `replaying the last 16 seconds of a finished book gains those seconds, not the book`() {
        val tenHours = 36_000_000L

        // Just after the finished book was opened at its end, the last seconds played again.
        listen(fromMs = tenHours - 16_000, seconds = 16, totalMs = tenHours)
        val report = clock.close()!!

        assertThat(report.progressDelta!!).isWithin(0.001).of(16_000 * 100.0 / tenHours)
        assertThat(report.endPercent).isEqualTo(100.0)
    }

    @Test
    fun `listening from 10 to 12 percent gains 2 points`() {
        listen(fromMs = 100_000, seconds = 20, totalMs = Total)

        val report = clock.close()!!

        assertThat(report.progressDelta!!).isWithin(0.0001).of(2.0)
        assertThat(report.endPercent!!).isWithin(0.0001).of(12.0)
    }

    @Test
    fun `before the book's length is known, the progress starts at the first tick where it is`() {
        listen(fromMs = 500_000, seconds = 5, totalMs = null)
        listen(fromMs = 505_500, seconds = 10, totalMs = Total)

        val report = clock.close()!!

        assertThat(report.progressDelta!!).isWithin(0.0001).of(1.0)
        assertThat(report.endPercent!!).isWithin(0.0001).of(51.55)
    }

    @Test
    fun `a jump forward gains no more than what was listened to`() {
        listen(fromMs = 100_000, seconds = 10, totalMs = Total)
        // A seek to 90 %: half a second later, the book is 790 s further.
        elapsed += 500
        clock.onPlaying(900_000, Total)
        listen(fromMs = 900_000, seconds = 10, totalMs = Total)

        val report = clock.close()!!

        // 20 s listened, and the seek's half second at most three times over.
        assertThat(report.progressDelta!!).isWithin(0.0001).of(2.15)
        assertThat(report.endPercent!!).isWithin(0.0001).of(91.0)
    }

    @Test
    fun `without the book's length, no progress is reported`() {
        listen(fromMs = 100_000, seconds = 20, totalMs = null)

        val report = clock.close()!!

        assertThat(report.progressDelta).isNull()
        assertThat(report.endPercent).isNull()
    }

    @Test
    fun `a new session after a long pause measures its own progress`() {
        listen(fromMs = 100_000, seconds = 20, totalMs = Total)
        clock.onPaused()
        advance(600)
        // After the pause, playback resumes far on: the new session starts there.
        listen(fromMs = 500_000, seconds = 20, totalMs = Total)

        val report = clock.close()!!

        assertThat(report.progressDelta!!).isWithin(0.0001).of(2.0)
        assertThat(report.endPercent!!).isWithin(0.0001).of(52.0)
    }

    @Test
    fun `nothing to report before any playing`() {
        advance(600)
        assertThat(clock.checkpoint()).isNull()
        assertThat(clock.close()).isNull()
    }

    private companion object {
        const val Total = 1_000_000L
    }
}
