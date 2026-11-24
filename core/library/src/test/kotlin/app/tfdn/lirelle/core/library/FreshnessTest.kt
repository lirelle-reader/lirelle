package app.tfdn.lirelle.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreshnessTest {
    private var now = 0L
    private val freshness = Freshness({ now }, maxAgeMillis = 120_000)

    @Test
    fun `nothing loaded is not stale`() {
        now = 999_999
        assertThat(freshness.isStale()).isFalse()
    }

    @Test
    fun `stale once older than the limit`() {
        freshness.markLoaded()
        now = 120_000
        assertThat(freshness.isStale()).isFalse()
        now = 120_001
        assertThat(freshness.isStale()).isTrue()
    }

    @Test
    fun `loading again resets the age`() {
        freshness.markLoaded()
        now = 200_000
        freshness.markLoaded()
        now = 250_000
        assertThat(freshness.isStale()).isFalse()
    }
}
