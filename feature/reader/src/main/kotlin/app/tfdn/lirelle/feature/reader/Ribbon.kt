package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val RibbonTag = "reader-ribbon"

private val RibbonWidth = 14.dp
private val RibbonHeight = 24.dp

/** How far the ribbon stands from the page's right edge. */
private val RibbonInset = 20.dp

/** How deep the notch at its bottom goes, as a share of its height. */
private const val NotchDepth = 0.25f

/**
 * The ribbon of a page holding a bookmark: give it the page's top-right corner, which stays on the
 * right whatever the direction of the book or of the language. It does nothing itself: a tap in
 * the corner is the page's.
 */
@Composable
internal fun Ribbon(colour: Color, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.reader_page_bookmarked)
    Canvas(
        modifier = modifier
            .absolutePadding(right = RibbonInset)
            .size(RibbonWidth, RibbonHeight)
            .testTag(RibbonTag)
            .semantics { contentDescription = label },
    ) {
        val notch = size.height * (1 - NotchDepth)
        val shape = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2, notch)
            lineTo(0f, size.height)
            close()
        }
        drawPath(shape, colour)
    }
}
