package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * The state without a network: its title and sentence wrap between words, and so does the button to
 * the downloads, which is always whole; "Retry" stays on one line.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class NoNetworkStateFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `every text fits and both actions can be reached`() {
        compose.setContent { LirelleTheme { NoNetworkState(onRetry = {}, onOpenDownloads = {}) } }

        val downloads = compose.activity.getString(R.string.no_network_open_downloads)

        val texts = compose.assertAllTextFits(wrappingLabels = setOf(downloads))

        // The title, the sentence and the two buttons; only "Retry" may be cut short.
        assertThat(texts).hasSize(4)
        assertThat(texts.filter { it.isEllipsized }.map { it.text })
            .containsNoneOf(downloads, compose.activity.getString(R.string.no_network_title))
        assertThat(texts.single { it.text == downloads }.isEllipsized).isFalse()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performScrollTo().assertIsWhollyShown()
        compose.onNodeWithTag(NoNetworkRetryTag).performScrollTo().assertIsWhollyShown()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp", "w1280dp-h800dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
