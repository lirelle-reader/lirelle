package app.tfdn.lirelle.feature.reader.settings

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import app.tfdn.lirelle.core.reader.settings.ReaderFont

/**
 * The fonts the preview of the Reading page is drawn with.
 *
 * The reading fonts ship as WOFF2, for the reading engine. The preview uses those faces where the platform
 * makes a typeface of them, and the platform's serif and sans-serif families where it does not: no font is
 * added to the app for the preview, which says of itself that it is approximate.
 */
internal object PreviewFonts {

    /** The regular face of each font that ships with the app, as the reading engine is given it. */
    private val ShippedFaces = mapOf(
        ReaderFont.Serif to "fonts/LirelleSerif-Regular.woff2",
        ReaderFont.Sans to "fonts/LirelleSans-Regular.woff2",
        ReaderFont.Dyslexic to "fonts/LirelleDyslexic-Regular.woff2",
    )

    /** The font family the preview uses for each reading font. */
    fun families(context: Context): Map<ReaderFont, FontFamily> = families(context.assets, ShippedFaces)

    /**
     * The font family for each reading font: its face among [faces] when the platform can use it, the
     * platform's own family otherwise. The book's own layout is always a generic serif.
     *
     * @param faces the asset holding the regular face of a font.
     */
    fun families(assets: AssetManager, faces: Map<ReaderFont, String>): Map<ReaderFont, FontFamily> =
        ReaderFont.entries.associateWith { font ->
            val face = faces[font]?.takeIf { font != ReaderFont.Book }?.let { typefaceOrNull(assets, it) }
            if (face != null) FontFamily(face) else font.platformFamily
        }

    /**
     * The typeface the platform makes of an asset, or null: the asset is missing, it is in a format the
     * platform does not read, or the platform answered with its default typeface in place of the asset's.
     */
    private fun typefaceOrNull(assets: AssetManager, path: String): Typeface? =
        runCatching { Typeface.Builder(assets, path).build() }
            .getOrNull()
            ?.takeUnless { it == Typeface.DEFAULT }

    private val ReaderFont.platformFamily: FontFamily
        get() = when (this) {
            ReaderFont.Book, ReaderFont.Serif -> FontFamily.Serif
            ReaderFont.Sans, ReaderFont.Dyslexic -> FontFamily.SansSerif
        }
}
