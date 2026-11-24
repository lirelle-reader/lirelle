package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** Whether a position of the server is somewhere else, for a file read by pages: an album of 300 pages. */
class PagePositionRuleTest {

    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val rule = NewerPositionRule()
    private val albumFile = FileId("31")

    private fun server(page: Int?, percent: Double, afterSeconds: Long = 60) =
        ReadingPosition(albumFile, cfi = null, percent = percent, updatedAt = t0.plusSeconds(afterSeconds), page = page)

    private fun local(page: Int?, percent: Double, afterSeconds: Long = 0) =
        LocalPosition(albumFile, cfi = null, percent = percent, at = t0.plusSeconds(afterSeconds), page = page)

    @Test
    fun `another page is another place, however close the percentages`() {
        assertThat(rule.isOffered(server(page = 151, percent = 50.33), local(page = 150, percent = 50.0))).isTrue()
        assertThat(rule.isOffered(server(page = 149, percent = 49.67), local(page = 150, percent = 50.0))).isTrue()
    }

    @Test
    fun `the same page is the same place, whatever the percentages`() {
        assertThat(rule.isOffered(server(page = 150, percent = 50.0), local(page = 150, percent = 50.0))).isFalse()
        assertThat(rule.isOffered(server(page = 150, percent = 75.0), local(page = 150, percent = 50.0))).isFalse()
    }

    @Test
    fun `the end shown as a last spread is the same place as the last page`() {
        // The reader gives a spread's first page; a server may keep the album's last.
        assertThat(rule.isOffered(server(page = 300, percent = 100.0), local(page = 299, percent = 100.0))).isFalse()
        assertThat(rule.isOffered(server(page = 299, percent = 100.0), local(page = 300, percent = 100.0))).isFalse()
        // Before the end, the page before the last is another place.
        assertThat(rule.isOffered(server(page = 300, percent = 100.0), local(page = 299, percent = 99.67))).isTrue()
    }

    @Test
    fun `without a page on one side, the percentages decide`() {
        assertThat(rule.isOffered(server(page = null, percent = 50.33), local(page = 150, percent = 50.0))).isFalse()
        assertThat(rule.isOffered(server(page = 151, percent = 50.33), local(page = null, percent = 50.0))).isFalse()
        assertThat(rule.isOffered(server(page = null, percent = 51.0), local(page = 150, percent = 50.0))).isTrue()
        assertThat(rule.isOffered(server(page = 2, percent = 0.67), current = null)).isTrue()
        assertThat(rule.isOffered(server(page = 1, percent = 0.33), current = null)).isFalse()
    }

    @Test
    fun `our own page echoed back is recognised by its page`() {
        rule.onOwnWrite(local(page = 150, percent = 50.0, afterSeconds = 10))

        val current = local(page = 152, percent = 50.67, afterSeconds = 20)
        assertThat(rule.isOffered(server(page = 150, percent = 50.0, afterSeconds = 300), current)).isFalse()
        assertThat(rule.isOffered(server(page = 151, percent = 50.33, afterSeconds = 300), current)).isTrue()
    }
}
