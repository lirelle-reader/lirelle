package app.tfdn.lirelle.feature.player.narration

import androidx.compose.runtime.Composable
import app.tfdn.lirelle.feature.player.ChapterNames
import app.tfdn.lirelle.feature.player.ChaptersSheet
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.SleepSheet
import app.tfdn.lirelle.feature.player.SpeedSheet
import app.tfdn.lirelle.feature.player.controller.NarrationUi

/** A narration's sections. */
private val Sections = ChapterNames(R.string.narration_sections, R.plurals.narration_sections_count)

/**
 * The narrated sections in the 3a chapters' sheet, titled "Sections": the ones heard greyed, the current one with
 * its time left. A tap on one goes to its start ([onChoose], with its place among the rows) and closes the sheet.
 * It takes the colours of the theme it is in: the narration's, from the panel.
 */
@Composable
fun SectionsSheet(state: NarrationUi, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    ChaptersSheet(state.asPlayerState(), onChoose, onDismiss, Sections)
}

/**
 * The sheet open, if any, over the narration: the 3a speed and sleep timer sheets (its stop at the end of a
 * section), and the sections.
 */
@Composable
internal fun NarrationSheets(
    sheet: NarrationSheet?,
    state: NarrationUi,
    actions: NarrationActions,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        NarrationSheet.Speed -> SpeedSheet(state.asPlayerState(), actions::setSpeed, onDismiss)
        NarrationSheet.Sleep -> SleepSheet(state.asPlayerState(), actions, actions, onDismiss, SectionSleep)
        NarrationSheet.Sections -> SectionsSheet(state, actions::goToSection, onDismiss)
        null -> Unit
    }
}
