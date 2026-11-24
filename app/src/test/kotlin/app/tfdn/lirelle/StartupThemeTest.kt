package app.tfdn.lirelle

import android.app.Application
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The theme of the window shown before Compose draws: it must follow the system dark mode. */
@RunWith(RobolectricTestRunner::class)
class StartupThemeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun startupTheme() = ContextThemeWrapper(application, application.applicationInfo.theme).theme

    private fun boolean(attribute: Int): Boolean {
        val value = TypedValue()
        check(startupTheme().resolveAttribute(attribute, value, true)) { "attribute not in the theme" }
        return value.data != 0
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `the start-up window is light in light mode`() {
        assertThat(boolean(android.R.attr.isLightTheme)).isTrue()
    }

    @Test
    @Config(qualifiers = "night")
    fun `the start-up window is dark in dark mode`() {
        assertThat(boolean(android.R.attr.isLightTheme)).isFalse()
    }

    @Test
    fun `the start-up window has no action bar`() {
        assertThat(boolean(android.R.attr.windowActionBar)).isFalse()
        assertThat(boolean(android.R.attr.windowNoTitle)).isTrue()
    }
}
