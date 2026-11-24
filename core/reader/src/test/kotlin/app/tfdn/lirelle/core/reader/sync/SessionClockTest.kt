package app.tfdn.lirelle.core.reader.sync

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class SessionClockTest {

    private val clocks = TestClocks()
    private val clock = SessionClock(clocks.monotonic, clocks.wall)

    @Test
    fun `nothing to report before any reading`() {
        clocks.advance(10.minutes)

        assertThat(clock.checkpoint()).isNull()
        assertThat(clock.close()).isNull()
        assertThat(clock.onForeground()).isNull()
    }

    @Test
    fun `a session starts at the first reading and reports the time, the progress and the place`() {
        clocks.advance(3.minutes)
        val start = clocks.now
        clock.onReading(20.0)
        clocks.advance(40.seconds)
        clock.onReading(20.5)
        clocks.advance(50.seconds)
        clock.onReading(21.25)

        val report = clock.close()!!

        assertThat(UUID.fromString(report.sessionId).toString()).isEqualTo(report.sessionId)
        assertThat(report.startedAt).isEqualTo(start)
        assertThat(report.endedAt).isEqualTo(start.plusSeconds(90))
        assertThat(report.activeSeconds).isEqualTo(90)
        assertThat(report.progressDelta).isEqualTo(1.25)
        assertThat(report.endPercent).isEqualTo(21.25)
    }

    @Test
    fun `going back in the book gives a negative progress`() {
        clock.onReading(50.0)
        clocks.advance(30.seconds)
        clock.onReading(42.0)

        assertThat(clock.close()!!.progressDelta).isEqualTo(-8.0)
    }

    @Test
    fun `the time on the last page counts up to the close`() {
        clock.onReading(20.0)
        clocks.advance(2.minutes)

        val report = clock.close()!!

        assertThat(report.activeSeconds).isEqualTo(120)
        assertThat(report.endedAt).isEqualTo(clocks.now)
    }

    @Test
    fun `time between two signals five minutes apart or more is not counted`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onReading(20.5)
        clocks.advance(5.minutes)
        clock.onReading(21.0)
        clocks.advance(299.seconds)
        clock.onReading(21.5)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(60 + 299)
    }

    @Test
    fun `a device left open on a page ends the session at the last signal`() {
        val start = clocks.now
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onReading(20.5)
        clocks.advance(45.minutes)

        val report = clock.close()!!

        assertThat(report.activeSeconds).isEqualTo(60)
        assertThat(report.endedAt).isEqualTo(start.plusSeconds(60))
    }

    @Test
    fun `under ten active seconds there is nothing to send`() {
        clock.onReading(20.0)
        clocks.advance(9.seconds)
        clock.onReading(20.5)

        assertThat(clock.checkpoint()).isNull()
        assertThat(clock.close()).isNull()
    }

    @Test
    fun `ten active seconds are enough`() {
        clock.onReading(20.0)
        clocks.advance(10.seconds)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(10)
    }

    @Test
    fun `a checkpoint keeps the id and its time only grows`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        val first = clock.checkpoint()!!
        clocks.advance(1.minutes)
        clock.onReading(22.0)
        val second = clock.checkpoint()!!
        clocks.advance(20.minutes)
        val third = clock.checkpoint()!!
        val last = clock.close()!!

        assertThat(setOf(first.sessionId, second.sessionId, third.sessionId, last.sessionId)).hasSize(1)
        assertThat(first.activeSeconds).isEqualTo(60)
        assertThat(second.activeSeconds).isEqualTo(120)
        assertThat(second.endPercent).isEqualTo(22.0)
        assertThat(third.activeSeconds).isEqualTo(120)
        assertThat(last.activeSeconds).isEqualTo(120)
        assertThat(last.startedAt).isEqualTo(first.startedAt)
    }

    @Test
    fun `a checkpoint does not stand for a page turn`() {
        clock.onReading(20.0)
        clocks.advance(4.minutes)
        clock.checkpoint()
        clocks.advance(4.minutes)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(240)
    }

    @Test
    fun `a short time away continues the session without counting the time away`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onBackground()
        val away = clock.checkpoint()!!
        clocks.advance(4.minutes)

        assertThat(clock.onForeground()).isNull()
        clocks.advance(30.seconds)
        clock.onReading(21.0)
        val report = clock.close()!!

        assertThat(report.sessionId).isEqualTo(away.sessionId)
        assertThat(away.activeSeconds).isEqualTo(60)
        assertThat(report.activeSeconds).isEqualTo(90)
    }

    @Test
    fun `time in the background is never counted, even when reported there`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onBackground()
        clocks.advance(2.minutes)

        assertThat(clock.checkpoint()!!.activeSeconds).isEqualTo(60)
        assertThat(clock.close()!!.activeSeconds).isEqualTo(60)
    }

    @Test
    fun `five minutes away close the session, and the next reading starts another`() {
        val start = clocks.now
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onBackground()
        clocks.advance(5.minutes)

        val closed = clock.onForeground()!!
        assertThat(closed.activeSeconds).isEqualTo(60)
        assertThat(closed.endedAt).isEqualTo(start.plusSeconds(60))
        assertThat(clock.checkpoint()).isNull()

        val restart = clocks.now
        clock.onReading(20.0)
        clocks.advance(30.seconds)
        clock.onReading(23.0)
        val next = clock.close()!!

        assertThat(next.sessionId).isNotEqualTo(closed.sessionId)
        assertThat(next.startedAt).isEqualTo(restart)
        assertThat(next.activeSeconds).isEqualTo(30)
        assertThat(next.progressDelta).isEqualTo(3.0)
    }

    @Test
    fun `a long time away with too short a session reports nothing and still starts anew`() {
        clock.onReading(20.0)
        clocks.advance(5.seconds)
        clock.onBackground()
        clocks.advance(10.minutes)

        assertThat(clock.onForeground()).isNull()
        clock.onReading(20.0)
        clocks.advance(20.seconds)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(20)
    }

    @Test
    fun `a session runs from the first reading until it closes, or ends away`() {
        assertThat(clock.started).isFalse()
        clock.onReading(20.0)
        assertThat(clock.started).isTrue()
        clock.close()
        assertThat(clock.started).isFalse()

        clock.onReading(20.0)
        clock.onBackground()
        clocks.advance(10.minutes)
        clock.onForeground()

        assertThat(clock.started).isFalse()
    }

    @Test
    fun `after a close the next reading is a new session`() {
        clock.onReading(20.0)
        clocks.advance(20.seconds)
        val first = clock.close()!!
        clock.onReading(20.0)
        clocks.advance(20.seconds)
        val second = clock.close()!!

        assertThat(second.sessionId).isNotEqualTo(first.sessionId)
        assertThat(clock.close()).isNull()
    }

    @Test
    fun `coming to the front without having left changes nothing`() {
        clock.onReading(20.0)
        clocks.advance(20.seconds)

        assertThat(clock.onForeground()).isNull()
        clocks.advance(20.seconds)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(40)
    }

    @Test
    fun `a page turn reported once in the background moves the place, not the clock`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onBackground()
        clocks.advance(1.seconds)
        clock.onReading(20.5)
        clocks.advance(4.minutes)

        assertThat(clock.checkpoint()!!.activeSeconds).isEqualTo(60)
        assertThat(clock.checkpoint()!!.endPercent).isEqualTo(20.5)
        assertThat(clock.onForeground()).isNull()
        clocks.advance(30.seconds)
        clock.onReading(21.0)

        assertThat(clock.close()!!.activeSeconds).isEqualTo(90)
    }

    @Test
    fun `a page turn reported in the background does not keep a session open past five minutes away`() {
        clock.onReading(20.0)
        clocks.advance(1.minutes)
        clock.onBackground()
        clocks.advance(1.seconds)
        clock.onReading(20.5)
        clocks.advance(5.minutes)

        val closed = clock.onForeground()!!

        assertThat(closed.activeSeconds).isEqualTo(60)
        assertThat(closed.endPercent).isEqualTo(20.5)
    }
}
