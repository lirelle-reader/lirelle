package app.tfdn.lirelle.feature.book

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

class FormattingTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `durations split into hours and minutes, rounded down`() {
        assertThat(durationParts(3_725)).isEqualTo(DurationParts(hours = 1, minutes = 2))
        assertThat(durationParts(59)).isEqualTo(DurationParts(hours = 0, minutes = 0))
        assertThat(durationParts(36_000)).isEqualTo(DurationParts(hours = 10, minutes = 0))
    }

    @Test
    fun `dates follow the locale and the time zone`() {
        val instant = Instant.parse("2026-03-04T23:30:00Z") // already the 5th in Paris

        assertThat(dateText(instant, Locale.FRANCE, paris)).isEqualTo("5 mars 2026")
        assertThat(dateText(instant, Locale.US, ZoneId.of("UTC"))).isEqualTo("Mar 4, 2026")
    }

    @Test
    fun `languages are named in the user's language`() {
        assertThat(languageName("fr", Locale.ENGLISH)).isEqualTo("French")
        assertThat(languageName("en", Locale.FRENCH)).isEqualTo("Anglais")
        assertThat(languageName("fr-FR", Locale.FRENCH)).isEqualTo("Français")
    }

    @Test
    fun `an unknown language code is shown as it is`() {
        assertThat(languageName("zz-unknown", Locale.ENGLISH)).isEqualTo("zz-unknown")
        assertThat(languageName("", Locale.ENGLISH)).isEqualTo("")
    }

    @Test
    fun `ratings use the locale's decimal mark and drop a useless decimal`() {
        assertThat(ratingText(4.5f, Locale.FRENCH)).isEqualTo("4,5")
        assertThat(ratingText(4.5f, Locale.ENGLISH)).isEqualTo("4.5")
        assertThat(ratingText(4f, Locale.ENGLISH)).isEqualTo("4")
    }
}
