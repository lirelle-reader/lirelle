package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val Viewport = 24f

private fun icon(name: String, mirrored: Boolean = false, outline: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, Viewport.dp, Viewport.dp, Viewport, Viewport, autoMirror = mirrored)
        .path(fill = SolidColor(Color.Black), pathBuilder = outline)
        .build()

/** The few icons of the sheets, drawn on the 24-unit grid of the Material icons. */
internal object SheetIcons {
    val Minus: ImageVector by lazy {
        icon("Minus") {
            moveTo(5f, 11f)
            horizontalLineTo(19f)
            verticalLineToRelative(2f)
            horizontalLineTo(5f)
            close()
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            moveTo(11f, 5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-6f)
            horizontalLineTo(5f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(6f)
            close()
        }
    }

    /** Points back: to the left, or to the right where text runs from right to left. */
    val Back: ImageVector by lazy {
        icon("Back", mirrored = true) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f)
            lineToRelative(-8f, 8f)
            lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            close()
        }
    }

    /** Points forward, to where a row leads. */
    val Forward: ImageVector by lazy {
        icon("Forward", mirrored = true) {
            moveTo(10f, 6f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12f)
            lineToRelative(-4.58f, 4.59f)
            lineTo(10f, 18f)
            lineToRelative(6f, -6f)
            close()
        }
    }

    val Expand: ImageVector by lazy {
        icon("Expand") {
            moveTo(16.59f, 8.59f)
            lineTo(12f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6f, 10f)
            lineToRelative(6f, 6f)
            lineToRelative(6f, -6f)
            close()
        }
    }
}
