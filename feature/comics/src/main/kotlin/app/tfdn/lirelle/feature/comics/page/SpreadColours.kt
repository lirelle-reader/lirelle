package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.graphics.Color
import app.tfdn.lirelle.core.comics.settings.Background

/** A grey half way between black and white. */
private val MidGrey = Color(0xFF808080)

/**
 * The colours of the page view.
 *
 * @property background what is around the pages.
 * @property content the texts and marks drawn on it: the one of black and white that reads best there.
 */
internal data class SpreadColours(val background: Color, val content: Color)

internal fun Background.colours(): SpreadColours = when (this) {
    Background.Black -> SpreadColours(background = Color.Black, content = Color.White)
    Background.Grey -> SpreadColours(background = MidGrey, content = Color.Black)
    Background.White -> SpreadColours(background = Color.White, content = Color.Black)
}
