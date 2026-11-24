package app.tfdn.lirelle.feature.comics.page

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of a page that could not be loaded overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class PageFailureTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(album())
    private var shown = false

    @Test
    fun `a page alone, for each reason the app gives`() {
        Reasons.forEach { reason ->
            state = album(spreadIndex = 12, pages = mapOf(12 to PageState.Failed(reason)))

            val texts = show()

            assertThat(texts).hasSize(3)
        }
    }

    @Test
    fun `two pages side by side, in a window wider than it is high`() {
        assumeTrue(screen == Wide)
        val failed = PageState.Failed(ApiError.ServerUnreachable)
        // The pages 99 and 100: numbers as long as an album's get.
        state = album(twoPages = true, settings = ComicSettings(background = Background.White))
            .copy(spreads = listOf(Spread(98, 99)), pages = mapOf(98 to failed, 99 to failed))

        val texts = show()

        assertThat(texts.count { "100" in it.text }).isEqualTo(1)
        assertThat(texts).hasSize(6)
    }

    private fun show(): List<LaidOutText> {
        if (!shown) {
            compose.setContent {
                LirelleTheme { ComicSpreadView(state, RecordingNavigation(), onToggleBars = {}) }
            }
            shown = true
        }
        compose.waitForIdle()
        return compose.assertAllTextFits()
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private val Reasons =
            listOf(ApiError.ServerUnreachable, ApiError.NotFound, ApiError.ServerFailed, ApiError.Unexpected)

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
