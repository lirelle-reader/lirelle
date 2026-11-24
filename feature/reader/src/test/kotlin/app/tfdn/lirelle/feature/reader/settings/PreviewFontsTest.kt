package app.tfdn.lirelle.feature.reader.settings

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The preview's fonts: the faces that ship with the app where the platform can draw them, the platform's own
 * families otherwise. Whether a device draws the shipped faces is seen on a device; here, every outcome gives
 * a family for every font and nothing fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewFontsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val platform = mapOf(
        ReaderFont.Book to FontFamily.Serif,
        ReaderFont.Serif to FontFamily.Serif,
        ReaderFont.Sans to FontFamily.SansSerif,
        ReaderFont.Dyslexic to FontFamily.SansSerif,
    )

    @Test
    fun `a face that is not in the app gives the platform's family`() {
        val missing = mapOf(
            ReaderFont.Serif to "fonts/Missing-Serif.woff2",
            ReaderFont.Sans to "fonts/Missing-Sans.woff2",
            ReaderFont.Dyslexic to "fonts/Missing-Dyslexic.woff2",
        )

        assertThat(PreviewFonts.families(context.assets, missing)).isEqualTo(platform)
    }

    @Test
    fun `a file that is not a font gives the platform's family`() {
        val notFonts = (ReaderFont.entries - ReaderFont.Book).associateWith { "fonts/LICENSES.md" }

        assertThat(PreviewFonts.families(context.assets, notFonts)).isEqualTo(platform)
    }

    @Test
    fun `with no face named, every font has the platform's family`() {
        assertThat(PreviewFonts.families(context.assets, emptyMap())).isEqualTo(platform)
    }

    @Test
    fun `the book's own layout is a generic serif, whatever is named for it`() {
        val named = mapOf(ReaderFont.Book to "fonts/LirelleSans-Regular.woff2")

        assertThat(PreviewFonts.families(context.assets, named)[ReaderFont.Book]).isEqualTo(FontFamily.Serif)
        assertThat(PreviewFonts.families(context)[ReaderFont.Book]).isEqualTo(FontFamily.Serif)
    }

    @Test
    fun `with the faces of the app, every font has a family and nothing fails`() {
        val families = PreviewFonts.families(context)

        assertThat(families.keys).containsExactlyElementsIn(ReaderFont.entries)
        // Either outcome is right for the three shipped fonts: only a device tells which one it is there.
        assertThat(families.getValue(ReaderFont.Book)).isEqualTo(FontFamily.Serif)
    }
}
