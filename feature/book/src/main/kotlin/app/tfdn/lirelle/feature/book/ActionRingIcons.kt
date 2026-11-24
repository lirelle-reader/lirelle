package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// ---------- The icons of the reading actions ----------

/** On the icon of Read that has sound waves: there when the reader can narrate the book. */
const val ActionRingWavesTag = "action-ring-waves"

private val IconSize = 28.dp
private const val IconViewport = 24f
private const val IconStroke = 1.8f

/** A page of a book, its fold, then three bars of sound of unequal heights: the design's own drawing. */
private const val NarratedBookPath =
    "M3 5.5c2.5-0.2 4.5 0.2 6 1.2v12c-1.5-1-3.5-1.4-6-1.2z" +
        "M9 6.7c1-0.7 2.3-1.1 4-1.2" +
        "M14 10v6M17 8v10M20 11v4"

/** An open book: its two pages, which meet at the fold. They are as tall as the page of the narrated book. */
private const val OpenBookPath =
    "M3 5.5c3.5-0.3 6.5 0.2 9 1.5v12c-2.5-1.3-5.5-1.8-9-1.5z" +
        "M21 5.5c-3.5-0.3-6.5 0.2-9 1.5v12c2.5-1.3 5.5-1.8 9-1.5z"

/** Headphones: the band over the head, then the two cups. */
private const val HeadphonesPath =
    "M4 14v-1.5a8 8 0 0 1 16 0v1.5" +
        "M4 14h2.5a1 1 0 0 1 1 1v3.5a1 1 0 0 1-1 1h-1a1.5 1.5 0 0 1-1.5-1.5z" +
        "M20 14h-2.5a1 1 0 0 0-1 1v3.5a1 1 0 0 0 1 1h1a1.5 1.5 0 0 0 1.5-1.5z"

/** An icon of the set: a drawing in strokes of one width with round ends, so that the three look alike. */
private fun strokeIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, IconSize, IconSize, IconViewport, IconViewport)
        .addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = IconStroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

/** A book with sound waves at its side: the reader can narrate the book. */
private val NarratedBook: ImageVector by lazy { strokeIcon("NarratedBook", NarratedBookPath) }
private val OpenBook: ImageVector by lazy { strokeIcon("OpenBook", OpenBookPath) }
private val Headphones: ImageVector by lazy { strokeIcon("Headphones", HeadphonesPath) }

/** Headphones for Listen; a book for Read, with sound waves at its side when the reader can narrate the book. */
@Composable
internal fun ActionIcon(ui: ActionRingUi, tint: Color) {
    // The button says what it is: its icon is not described.
    val size = Modifier.size(IconSize)
    when {
        ui.kind == ActionKind.Listen -> Icon(Headphones, null, size, tint)
        ui.narrated -> Icon(NarratedBook, null, size.testTag(ActionRingWavesTag), tint)
        else -> Icon(OpenBook, null, size, tint)
    }
}
