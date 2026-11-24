package app.tfdn.lirelle.feature.book

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val SecondsPerMinute = 60L
private const val MinutesPerHour = 60L

internal data class DurationParts(val hours: Long, val minutes: Long)

internal fun durationParts(seconds: Long): DurationParts {
    val minutes = seconds / SecondsPerMinute
    return DurationParts(hours = minutes / MinutesPerHour, minutes = minutes % MinutesPerHour)
}

internal fun dateText(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(instant.atZone(zone).toLocalDate())

/** The name of a language code such as "fr" or "en-GB", or the code itself when unknown. */
internal fun languageName(code: String, locale: Locale): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
    // An unknown code comes back unchanged (or empty for a malformed tag).
    if (name.isBlank() || name.equals(code.substringBefore('-'), ignoreCase = true)) return code
    return name.replaceFirstChar { it.titlecase(locale) }
}

internal fun ratingText(rating: Float, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(rating)

@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
internal fun durationText(seconds: Long): String {
    val parts = durationParts(seconds)
    return if (parts.hours > 0) {
        stringResource(R.string.duration_hours_minutes, parts.hours, parts.minutes)
    } else {
        stringResource(R.string.duration_minutes, parts.minutes)
    }
}

@Composable
internal fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
