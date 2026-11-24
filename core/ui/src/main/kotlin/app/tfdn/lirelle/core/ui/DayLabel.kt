package app.tfdn.lirelle.core.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val NoBreakSpace = ' '

/** The words for the two days that are not told by their date. */
data class DayWords(val today: String, val yesterday: String)

/**
 * "today", "yesterday", then the date ("Nov 2", with the year when it is not this one): the day of
 * a moment as seen from another, in [zone], in [locale]. Lower case, to follow a separator in a
 * line of details. The parts of a date are not parted at the end of a line.
 */
class DayLabels(private val locale: Locale, private val zone: ZoneId, private val words: DayWords) {
    private val thisYear by lazy(LazyThreadSafetyMode.NONE) { formatter("MMMd") }
    private val otherYear by lazy(LazyThreadSafetyMode.NONE) { formatter("yMMMd") }

    fun of(at: Instant, now: Instant): String {
        val day = at.atZone(zone).toLocalDate()
        val current = now.atZone(zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(day, current)) {
            0L -> words.today
            1L -> words.yesterday
            else -> day.format(if (day.year == current.year) thisYear else otherYear).replace(' ', NoBreakSpace)
        }
    }

    private fun formatter(skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** The [DayLabels] of the app's language and the device's zone, kept while they stay the same. */
@Composable
fun rememberDayLabels(zone: ZoneId = ZoneId.systemDefault()): DayLabels {
    val locale = LocalConfiguration.current.locales[0]
    val words = DayWords(
        today = stringResource(R.string.date_today),
        yesterday = stringResource(R.string.date_yesterday),
    )
    return remember(locale, zone, words) { DayLabels(locale, zone, words) }
}

/** The day of [at] as seen from [now]: see [DayLabels]. */
@Composable
fun dayLabel(at: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    rememberDayLabels(zone).of(at, now)
