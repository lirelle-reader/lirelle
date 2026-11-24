package app.tfdn.lirelle.feature.reader.narration

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import app.tfdn.lirelle.feature.player.narration.NarrationStrip
import app.tfdn.lirelle.feature.reader.ReaderUiState

internal const val NarrationStripTag = "reader-narration-strip"

/**
 * The strip's ways back to the sentence heard, over the page, under the controls' top bar when it shows:
 * coming and going, they leave the page's height alone, so the page is not laid out again under the reader's
 * eyes. With nothing above them they are centred on the running header ([headerCentre], in pixels from the top),
 * which they hide while they show, in the band above the text, and kept clear of the status bar and the display's
 * cut-out.
 */
@Composable
internal fun NarrationWaysBack(
    state: ReaderUiState,
    actions: ReaderNarrationActions,
    place: NarrationPlace,
    headerCentre: Int,
) {
    val strip = state.narration.strip
    if (place.controls == null || strip == null || state.narration.active?.loaded != true) return
    val clear = !state.controlsShown && state.newerPosition == null
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    val inset = if (clear) Modifier.onHeaderLine(headerCentre, top) else Modifier
    NarrationStrip(
        colors = place.colors,
        onBack = actions::backToSentence,
        onReadPage = actions::readThisPage.takeIf { strip == StripUi.BackAndReadPage },
        modifier = inset.testTag(NarrationStripTag),
    )
}

/** Placed with its middle on [centre] (pixels from the top), not above the [top] insets. */
@Composable
private fun Modifier.onHeaderLine(centre: Int, top: WindowInsets): Modifier {
    val density = LocalDensity.current
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val y = maxOf(centre - placeable.height / 2, top.getTop(density))
        layout(placeable.width, y + placeable.height) { placeable.place(0, y) }
    }
}
