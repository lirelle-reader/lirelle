package app.tfdn.lirelle.shell

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the Downloads page and of the question it asks overflows, is clipped or breaks mid-word, on a
 * narrow phone, a phone and a tablet, in both languages, at the default and the largest text size, on its own
 * and in a pane. Its title is never cut short.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class DownloadsSettingsTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val large =
        DownloadsSettingsState(false, books = 1_234, usedBytes = 123_400_000_000, freeBytes = 987_600_000_000)

    private fun show(state: DownloadsSettingsState, onBack: (() -> Unit)?) {
        compose.setContent {
            LirelleTheme { DownloadsSettingsPage(state, onAllowMetered = {}, onDeleteAll = {}, onBack = onBack) }
        }
    }

    @Test
    fun `the page fits with large downloads, mobile network off`() {
        show(large, onBack = {})

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    @Test
    fun `the page fits in a pane, with nothing downloaded, mobile network on`() {
        show(DownloadsSettingsState(true, books = 0, usedBytes = 0, freeBytes = 987_600_000_000), onBack = null)

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    @Test
    fun `the question before deleting everything fits`() {
        show(large, onBack = {})

        compose.onNodeWithText(context.getString(R.string.more_downloads_delete_all)).performScrollTo().performClick()

        compose.assertAllTextFits()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w411dp-h891dp-420dpi", "w1280dp-h800dp-xhdpi").flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
