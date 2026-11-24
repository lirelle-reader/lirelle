package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class NewerPositionRuleTest {

    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val here = "epubcfi(/6/4!/4/2/1:0)"
    private val there = "epubcfi(/6/18!/4/2/1:0)"
    private val rule = NewerPositionRule()

    private fun server(cfi: String?, percent: Double, afterSeconds: Long?) =
        ReadingPosition(FileId("12"), cfi, percent, afterSeconds?.let(t0::plusSeconds))

    private fun local(cfi: String?, percent: Double, afterSeconds: Long = 0) =
        LocalPosition(FileId("12"), cfi, percent, t0.plusSeconds(afterSeconds))

    @Test
    fun `a later position at another place is offered`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))

        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 60), local(here, 10.0))).isTrue()
    }

    @Test
    fun `it is offered again as long as it was not answered`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))
        val newer = server(there, 40.0, afterSeconds = 60)

        assertThat(rule.isOffered(newer, local(here, 10.0))).isTrue()
        assertThat(rule.isOffered(newer, local(here, 10.0))).isTrue()
    }

    @Test
    fun `the position read at opening is not offered`() {
        val read = server(there, 40.0, afterSeconds = 60)
        rule.onRead(read)

        assertThat(rule.isOffered(read, local(here, 10.0))).isFalse()
    }

    @Test
    fun `an older position is not offered, wherever it is`() {
        rule.onRead(server(here, 10.0, afterSeconds = 60))

        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 30), local(here, 10.0))).isFalse()
    }

    @Test
    fun `a later date alone is not enough`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))

        assertThat(rule.isOffered(server(here, 10.0, afterSeconds = 60), local(here, 10.0))).isFalse()
        assertThat(rule.isOffered(server(here, 10.2, afterSeconds = 60), local(here, 10.0))).isFalse()
    }

    @Test
    fun `without a place on one side, the percentages decide`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))

        assertThat(rule.isOffered(server(null, 10.5, afterSeconds = 60), local(here, 10.0))).isFalse()
        assertThat(rule.isOffered(server(null, 10.6, afterSeconds = 60), local(here, 10.0))).isTrue()
        assertThat(rule.isOffered(server(there, 9.4, afterSeconds = 60), local(null, 10.0))).isTrue()
        assertThat(rule.isOffered(server(there, 9.6, afterSeconds = 60), local(null, 10.0))).isFalse()
    }

    @Test
    fun `two positions at the very end are the same place, whatever their precise places`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))

        assertThat(rule.isOffered(server(there, 100.0, afterSeconds = 60), local(here, 100.0))).isFalse()
        // Our own end echoed back, the reader gone back a little since.
        rule.onOwnWrite(local(here, 100.0, afterSeconds = 70))
        assertThat(rule.isOffered(server(there, 100.0, afterSeconds = 300), local(here, 98.0, 80))).isFalse()
    }

    @Test
    fun `a position near the end is still another place than the very end`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))

        assertThat(rule.isOffered(server(there, 99.4, afterSeconds = 60), local(here, 100.0))).isTrue()
        assertThat(rule.isOffered(server(there, 100.0, afterSeconds = 60), local(here, 99.4))).isTrue()
    }

    @Test
    fun `a file never opened on the server offers nothing`() {
        assertThat(rule.isOffered(server(null, 0.0, afterSeconds = null), local(here, 10.0))).isFalse()
    }

    @Test
    fun `with nothing read or written yet, a position elsewhere is offered`() {
        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 60), local(here, 10.0))).isTrue()
        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 60), current = null)).isTrue()
        assertThat(rule.isOffered(server(null, 0.3, afterSeconds = 60), current = null)).isFalse()
    }

    @Test
    fun `an ignored position is not offered again until a still newer one appears`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))
        val ignored = server(there, 40.0, afterSeconds = 60)
        rule.ignore(ignored)

        assertThat(rule.isOffered(ignored, local(here, 10.0))).isFalse()
        assertThat(rule.isOffered(server(there, 45.0, afterSeconds = 60), local(here, 10.0))).isFalse()
        assertThat(rule.isOffered(server(there, 45.0, afterSeconds = 61), local(here, 10.0))).isTrue()
    }

    @Test
    fun `a position written by another device before our last write is not offered`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))
        rule.onOwnWrite(local(here, 12.0, afterSeconds = 120))

        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 60), local(here, 12.0, 120))).isFalse()
        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 180), local(here, 12.0, 120))).isTrue()
    }

    @Test
    fun `our own write echoed back is never offered, even stamped later by the server's clock`() {
        val mid = "epubcfi(/6/10!/4/2/1:0)"
        rule.onRead(server(here, 10.0, afterSeconds = 0))
        rule.onOwnWrite(local(mid, 20.0, afterSeconds = 10))
        rule.onOwnWrite(local(there, 40.0, afterSeconds = 20))

        // The server's clock is five minutes ahead of the device's.
        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 320), local(there, 40.0, 20))).isFalse()
        // The reader has moved on since; the server still holds the write before, or the last one.
        assertThat(rule.isOffered(server(mid, 20.0, afterSeconds = 310), local(here, 41.0, 30))).isFalse()
        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 320), local(here, 41.0, 30))).isFalse()
    }

    @Test
    fun `only the last few own writes are remembered`() {
        rule.onRead(server(here, 10.0, afterSeconds = 0))
        rule.onOwnWrite(local(there, 40.0, afterSeconds = 1))
        repeat(OwnWritesRemembered) { rule.onOwnWrite(local("epubcfi(/6/$it!/4)", 41.0 + it, afterSeconds = 2)) }

        assertThat(rule.isOffered(server(there, 40.0, afterSeconds = 320), local(here, 60.0, 2))).isTrue()
    }
}
