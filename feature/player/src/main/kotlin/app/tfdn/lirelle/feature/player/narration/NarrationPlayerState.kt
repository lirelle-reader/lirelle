package app.tfdn.lirelle.feature.player.narration

import androidx.compose.runtime.Composable
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.SleepNames
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.sleepLeftText
import kotlin.math.roundToLong

/** A narration's timer stops at the end of a section, not of a chapter. */
internal val SectionSleep = SleepNames(R.string.narration_sleep_end_of_section, R.string.narration_sleep_stops_section)

/**
 * The narration as the 3a player's state, for the 3a pieces it reuses: its sections as the chapters, the
 * narration's length (its sections' when all are known) as the book's, and the place on it from the time left.
 */
internal fun NarrationUi.asPlayerState(): PlayerUiState {
    val total = sections.takeIf { rows -> rows.all { it.durationMs != null } }?.sumOf { it.durationMs ?: 0L }
    val leftAtOne = (timeLeftMs * speed.toDouble()).roundToLong()
    return PlayerUiState(
        bookId = bookId,
        title = title,
        chapterIndex = (sectionNumber - 1).coerceAtLeast(0),
        chapterTitle = sectionTitle,
        chapterElapsedMs = elapsedMs,
        chapterDurationMs = sectionDurationMs.takeIf { it > 0 },
        chapterCount = sectionCount,
        bookTimeMs = total?.let { (it - leftAtOne).coerceAtLeast(0) } ?: 0,
        totalMs = total,
        playing = playing,
        speed = speed,
        sleep = sleep,
        chapters = sections,
        settings = settings,
        narrationFileId = fileId,
    )
}

/** What is left of the narration's sleep timer: its minutes, the time to the end of the section; null without one. */
@Composable
internal fun narrationSleepLeft(state: NarrationUi): String? = sleepLeftText(state.asPlayerState(), SectionSleep)
