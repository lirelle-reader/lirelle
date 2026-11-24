package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.session.ListeningSessionClock
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class ListeningSessionsTest {

    private var elapsed = 0L
    private val start = Instant.parse("2026-11-15T10:00:00Z")
    private val wall = object : Clock() {
        override fun instant(): Instant = start.plusMillis(elapsed)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
    private val sent = mutableListOf<ReadingSessionReport>()
    private val sessions = ListeningSessions({ ListeningSessionClock(MonotonicClock { elapsed }, wall) }) { _, report ->
        sent += report
    }
    private var bookTime = 600_000L

    /** Playing at speed 1 for [seconds], a tick every half second, in a book of 10 hours. */
    private fun listen(seconds: Int) {
        repeat(seconds * 2) {
            elapsed += 500
            bookTime += 500
            sessions.playing(FileId("7"), bookTime, 36_000_000)
        }
    }

    @Test
    fun `while playing, the session is reported at each 5 minutes of listening, then at the pause`() {
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(12 * 60)
        sessions.paused()

        assertThat(sent.map { it.activeSeconds }).containsExactly(300, 600, 720).inOrder()
        assertThat(sent.map { it.sessionId }.distinct()).hasSize(1)
        assertThat(sent.map { it.endedAt }).isInOrder()
        assertThat(sent.last().progressDelta!!).isWithin(0.001).of(720_000 * 100.0 / 36_000_000)
    }

    @Test
    fun `a pause on the way keeps the 5 minute marks`() {
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(3 * 60)
        sessions.paused()
        elapsed += 60_000
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(9 * 60)

        assertThat(sent.map { it.activeSeconds }).containsExactly(180, 300, 600).inOrder()
        assertThat(sent.map { it.sessionId }.distinct()).hasSize(1)
    }

    @Test
    fun `nothing is reported while paused`() {
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(4 * 60)
        sessions.paused()
        elapsed += 4 * 60_000

        assertThat(sent.map { it.activeSeconds }).containsExactly(240)
    }

    @Test
    fun `a new session after a long pause counts its own 5 minutes`() {
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(6 * 60)
        sessions.paused()
        elapsed += 10 * 60_000
        sessions.playing(FileId("7"), bookTime, 36_000_000)
        listen(4 * 60)

        assertThat(sent.map { it.activeSeconds }).containsExactly(300, 360, 360).inOrder()
        listen(60)
        assertThat(sent.last().activeSeconds).isEqualTo(300)
        assertThat(sent.last().sessionId).isNotEqualTo(sent.first().sessionId)
    }
}
