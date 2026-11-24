package app.tfdn.lirelle.core.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DayLabelTest {

    @get:Rule
    val compose = createComposeRule()

    private val paris = ZoneId.of("Europe/Paris")

    /** Saturday 14 November 2026, 00:30 in Paris: still the 13th in UTC. */
    private val now = Instant.parse("2026-11-13T23:30:00Z")

    private val words = DayWords(today = "today", yesterday = "yesterday")

    private fun label(at: String, locale: Locale = Locale.ENGLISH, from: Instant = now) =
        DayLabels(locale, paris, words).of(Instant.parse(at), from)

    @Test
    fun `the same day in the device's zone is today`() {
        assertThat(label("2026-11-13T23:05:00Z")).isEqualTo("today")
    }

    @Test
    fun `the day before in the device's zone is yesterday, even a few minutes ago`() {
        assertThat(label("2026-11-13T22:50:00Z")).isEqualTo("yesterday")
        assertThat(label("2026-11-12T23:10:00Z")).isEqualTo("yesterday")
    }

    @Test
    fun `earlier this year is the day and the month`() {
        assertThat(label("2026-11-02T10:00:00Z")).isEqualTo("Nov\u00A02")
        // The day and the month are not parted at the end of a line.
        assertThat(label("2026-11-02T10:00:00Z", Locale.FRENCH)).isEqualTo("2\u00A0nov.")
    }

    @Test
    fun `another year adds the year`() {
        assertThat(label("2025-03-02T10:00:00Z")).isEqualTo("Mar\u00A02,\u00A02025")
        assertThat(label("2025-03-02T10:00:00Z", Locale.FRENCH)).isEqualTo("2\u00A0mars\u00A02025")
    }

    @Test
    fun `a moment ahead of the clock on the same day is today, on a later day its date`() {
        assertThat(label("2026-11-14T08:00:00Z")).isEqualTo("today")
        assertThat(label("2026-11-20T08:00:00Z")).isEqualTo("Nov\u00A020")
    }

    @Test
    fun `across the new year, the last day of the old one is yesterday and earlier days have their year`() {
        // 1 January 2027, 00:30 in Paris.
        val newYear = Instant.parse("2026-12-31T23:30:00Z")

        assertThat(label("2026-12-31T10:00:00Z", from = newYear)).isEqualTo("yesterday")
        assertThat(label("2026-12-20T10:00:00Z", from = newYear)).isEqualTo("Dec\u00A020,\u00A02026")
        assertThat(label("2026-12-31T23:10:00Z", from = newYear)).isEqualTo("today")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the words come from the app's language`() {
        compose.setContent { Text(dayLabel(now, now)) }

        compose.onNodeWithText("aujourd’hui").assertIsDisplayed()
    }
}
