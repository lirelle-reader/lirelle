package app.tfdn.lirelle.core.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.Duration
import java.time.Instant

private val JustNowWithin: Duration = Duration.ofMinutes(1)

/**
 * "2 hours ago", "yesterday": [at] as seen from [now], in the device's language. A date a little ahead
 * of the device's clock (the server's) counts as now; under a minute it reads [justNow] when given.
 */
fun relativeTime(at: Instant, now: Instant, justNow: String? = null): String {
    val then = minOf(at, now)
    if (justNow != null && Duration.between(then, now) < JustNowWithin) return justNow
    return DateUtils.getRelativeTimeSpanString(then.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS)
        .toString()
}

/**
 * [relativeTime] in the middle of a sentence, "(2 hours ago)", « (il y a 2 heures) »: under a minute
 * "just now", and its first letter lower case as the language writes it there.
 */
@Composable
fun relativeTimeInSentence(at: Instant, now: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return relativeTime(at, now, stringResource(R.string.relative_just_now)).replaceFirstChar { it.lowercase(locale) }
}
