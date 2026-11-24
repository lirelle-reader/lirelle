package app.tfdn.lirelle.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.Shortened
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor

private const val MillisPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val MinutesPerHour = 60L
private const val MillisPerMinute = MillisPerSecond * SecondsPerMinute

/** "Anne Verlaine · read by Thomas Rivière" (spec 5.1); null when the line is not shown. */
@Composable
@ReadOnlyComposable
internal fun creditsText(credits: CreditsLine): String? = when (credits) {
    is CreditsLine.AuthorsAndNarrators ->
        stringResource(R.string.player_credits_both, namesText(credits.authors), namesText(credits.narrators))
    is CreditsLine.AuthorsOnly -> namesText(credits.authors)
    is CreditsLine.NarratorsOnly -> stringResource(R.string.player_credits_narrators, namesText(credits.narrators))
    CreditsLine.None -> null
}

/** "Anne Verlaine", "Anne Verlaine and Paul Ardent", "Anne Verlaine and 2 others". */
@Composable
@ReadOnlyComposable
private fun namesText(names: Shortened): String = when {
    names.second != null -> stringResource(R.string.player_names_two, names.first, names.second.orEmpty())
    names.others > 0 -> pluralStringResource(R.plurals.player_names_others, names.others, names.first, names.others)
    else -> names.first
}

/** "12:04", "1:02:03": a time on a clock, to the second below. */
internal fun clockTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / MillisPerSecond
    val hours = seconds / (SecondsPerMinute * MinutesPerHour)
    val minutes = seconds / SecondsPerMinute % MinutesPerHour
    val rest = seconds % SecondsPerMinute
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

/** "23 min", "9 h 12": a time left, to the minute above, so that it reads 0 only once it is over. */
@Composable
@ReadOnlyComposable
internal fun durationText(ms: Long): String {
    val minutes = minutesLeft(ms)
    return if (minutes < MinutesPerHour) {
        stringResource(R.string.player_duration_minutes, minutes.toInt())
    } else {
        val hours = (minutes / MinutesPerHour).toInt()
        stringResource(R.string.player_duration_hours, hours, (minutes % MinutesPerHour).toInt())
    }
}

/** The minutes [durationText] shows: rounded up. */
private fun minutesLeft(ms: Long): Long = (ms.coerceAtLeast(0) + MillisPerMinute - 1) / MillisPerMinute

/** "1.25×", « 1,25× »: the speed with the decimal mark of the language, without trailing zeros. */
@Composable
internal fun speedText(speed: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(speed, locale) {
        // Through its decimal text: 1.05f is 1.0499999 as a double.
        DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(BigDecimal(speed.toString()))
    }
    return stringResource(R.string.player_speed_value, number)
}

/**
 * "Chapter 12 of 48 · 37% · 9 h 12 left", "Part 12 of 48 · …" for a book in parts: what is known of it, what is
 * not left out; null when nothing is known.
 */
@Composable
@ReadOnlyComposable
internal fun bookLineText(state: PlayerUiState): String? {
    val place = if (state.inParts) R.string.player_book_part else R.string.player_book_chapter
    val parts = listOfNotNull(
        state.chapterCount.takeIf { it > 0 }?.let { stringResource(place, state.chapterIndex + 1, it) },
        state.bookPercent?.let { stringResource(R.string.player_book_percent, floor(it).toInt()) },
        state.bookTimeLeftMs?.let {
            pluralStringResource(R.plurals.player_book_left, minutesLeft(it).toInt(), durationText(it))
        },
    )
    if (parts.isEmpty()) return null
    var line = parts.first()
    parts.drop(1).forEach { line = stringResource(R.string.player_joined, line, it) }
    return line
}
