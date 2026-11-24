package app.tfdn.lirelle.feature.reader

import android.app.Application
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ReaderChromeThemeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun contrast(one: Color, other: Color): Float {
        val lighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `text keeps its contrast on every surface of the reader, in every theme`() {
        ReaderTheme.entries.forEach { theme ->
            val scheme = theme.colours(systemDark = true).toColorScheme()
            val pairs = mapOf(
                "background" to (scheme.onBackground to scheme.background),
                "surface" to (scheme.onSurface to scheme.surface),
                "surface variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "raised surface" to (scheme.onSurface to scheme.surfaceContainerHighest),
                "secondary container" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "primary" to (scheme.onPrimary to scheme.primary),
                "text button on a bar" to (scheme.primary to scheme.surface),
                "inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
            )
            pairs.forEach { (name, colours) ->
                assertWithMessage("$theme, $name").that(contrast(colours.first, colours.second)).isAtLeast(4.5f)
            }
        }
    }

    @Test
    fun `the page and the bars are those of the reading theme`() {
        val colours = ReaderTheme.Sepia.colours(systemDark = false)
        val scheme = colours.toColorScheme()

        assertThat(scheme.background).isEqualTo(colours.background)
        assertThat(scheme.onBackground).isEqualTo(colours.foreground)
        assertThat(scheme.surface).isEqualTo(colours.barBackground)
        assertThat(scheme.onSurface).isEqualTo(colours.barForeground)
        assertThat(scheme.primary).isEqualTo(colours.link)
    }

    @Test
    fun `the accent is the link colour, and nothing is tinted by being raised`() {
        ReaderTheme.entries.forEach { theme ->
            val colours = theme.colours(systemDark = false)
            val scheme = colours.toColorScheme()

            assertThat(setOf(scheme.primary, scheme.secondary, scheme.tertiary)).containsExactly(colours.link)
            assertThat(scheme.surfaceTint).isEqualTo(Color.Transparent)
            assertThat(scheme.scrim).isEqualTo(Color.Black)
        }
    }

    @Test
    fun `outlines and what is switched off can be told from the bars, in every theme`() {
        ReaderTheme.entries.forEach { theme ->
            val scheme = theme.colours(systemDark = true).toColorScheme()

            // The ring of a control, the thumb and the edge of a switch that is off.
            assertWithMessage("$theme, outline").that(contrast(scheme.outline, scheme.surface)).isAtLeast(3f)
            // What is disabled is the text colour, faded: it is still that of the bars.
            assertWithMessage("$theme, disabled").that(scheme.onSurface).isEqualTo(scheme.onSurfaceVariant)
            // The track of a switch that is off, and the part of a slider not reached yet.
            assertThat(scheme.surfaceContainerHighest).isNotEqualTo(scheme.surface)
            assertThat(scheme.secondaryContainer).isNotEqualTo(scheme.surface)
        }
    }

    @Test
    fun `a web address is handed to the browser`() {
        application.openInBrowser("https://example.org/page")

        val intent = shadowOf(application).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("https://example.org/page")
    }

    @Test
    fun `a device without a browser says so`() {
        shadowOf(application).checkActivities(true)

        application.openInBrowser("https://example.org/page")

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(application.getString(R.string.reader_no_browser))
    }
}
