package app.tfdn.lirelle.source.kavita.library

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class KavitaValuesTest {

    @Test
    fun `a time is read in UTC, with or without its zone`() {
        val expected = Instant.parse("2026-11-26T07:53:46.781Z")

        assertThat(utcInstant("2026-11-26T07:53:46.781")).isEqualTo(expected)
        assertThat(utcInstant("2026-11-26T07:53:46.781Z")).isEqualTo(expected)
        assertThat(utcInstant("2026-11-26T08:53:46.781+01:00")).isEqualTo(expected)
        assertThat(yearOf("1961-07-31T00:00:00Z")).isEqualTo(1961)
    }

    @Test
    fun `year 1 and what cannot be read are no time`() {
        assertThat(utcInstant("0001-01-01T00:00:00")).isNull()
        assertThat(utcInstant("0001-01-01T00:00:00Z")).isNull()
        assertThat(utcInstant("yesterday")).isNull()
        assertThat(utcInstant(" ")).isNull()
        assertThat(utcInstant(null)).isNull()
    }
}
