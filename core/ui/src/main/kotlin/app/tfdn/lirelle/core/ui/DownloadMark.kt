package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import kotlinx.coroutines.flow.StateFlow

/**
 * The mark of each book the device keeps something of, as the shell knows it. Null outside the shell
 * (previews, tests), where no cover is marked.
 */
val LocalDownloadMarks = staticCompositionLocalOf<StateFlow<Map<BookId, DownloadMark>>?> { null }

/** The tag of the badge of [mark]: one per mark, the drawing is not text. */
fun downloadMarkTag(mark: DownloadMark): String = "download-mark-${mark.name}"

/** The mark of [bookId] here and now; null where the shell gives none (previews, tests). */
@Composable
fun downloadMarkOf(bookId: BookId): DownloadMark? {
    val marks = LocalDownloadMarks.current ?: return null
    return marks.collectAsStateWithLifecycle().value[bookId]
}

/**
 * What a mark says, in words, for the description of the item that holds the cover: the badge itself is
 * not announced. Null without a mark.
 */
@Composable
fun downloadMarkWords(mark: DownloadMark?): String? = when (mark) {
    null -> null
    DownloadMark.Kept -> stringResource(R.string.book_downloaded_description)
    DownloadMark.InProgress -> stringResource(R.string.book_downloading_description)
    DownloadMark.Update -> stringResource(R.string.book_update_description)
}

/** The side of the square the drawings are given in. */
private const val Grid = 24f
private const val ArrowStroke = 2.8f
private const val RingStroke = 3f
private const val RingRadius = 8.5f

/** A shaft down to an arrow head, over the line of the device. */
private const val ToDeviceArrow = "M12 4v11M7 11l5 5 5-5M5 20h14"

/** The quarter of the ring that is lit, from its top. */
private const val RingQuarter = "M12 3.5a8.5 8.5 0 0 1 8.5 8.5"

/** Most of a circle, and the head of the arrow where it ends. */
private const val RefreshArrow = "M20 12a8 8 0 1 1-2.6-5.9M20 4v4.5h-4.5"

private val DiscSize = 24.dp
private val ArrowSize = 14.dp
private val RingSize = 16.dp

/**
 * The mark of a book on this device, 24 dp: a "to device" arrow for a kept book, a ring that does not turn
 * for one being fetched, a refresh arrow on the accent colour for one with a newer version to fetch. The
 * three differ by their drawing, not by colour alone.
 */
@Composable
fun DownloadMarkBadge(mark: DownloadMark, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isUpdate = mark == DownloadMark.Update
    val ink = if (isUpdate) colors.onPrimary else colors.primary
    val dim = colors.outlineVariant
    Box(
        modifier = modifier
            .testTag(downloadMarkTag(mark))
            .size(DiscSize)
            .background(if (isUpdate) colors.primary else colors.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(if (mark == DownloadMark.InProgress) RingSize else ArrowSize)) {
            // The drawings are given in a 24-unit square, whatever the size of the canvas.
            scale(size.minDimension / Grid, pivot = Offset.Zero) {
                when (mark) {
                    DownloadMark.Kept -> drawPath(parsed(ToDeviceArrow), ink, style = roundStroke(ArrowStroke))
                    DownloadMark.InProgress -> ring(ink, dim)
                    DownloadMark.Update -> drawPath(parsed(RefreshArrow), ink, style = roundStroke(ArrowStroke))
                }
            }
        }
    }
}

private fun roundStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun parsed(pathData: String): Path = PathParser().parsePathString(pathData).toPath()

/** A quarter of the ring in the accent colour over the whole of it, dim: a moment of a spinner, still. */
private fun DrawScope.ring(ink: Color, dim: Color) {
    drawCircle(dim, RingRadius, Offset(Grid / 2, Grid / 2), style = roundStroke(RingStroke))
    drawPath(parsed(RingQuarter), ink, style = roundStroke(RingStroke))
}
