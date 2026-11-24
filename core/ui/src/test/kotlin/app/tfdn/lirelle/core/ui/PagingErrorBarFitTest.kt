package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** The bar under a list whose load failed: its message wraps between words, its button stays whole. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class PagingErrorBarFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `every message fits beside or above its retry`() {
        compose.setContent {
            LirelleTheme {
                // Scrolling, so that each bar has the height it asks for: five of them are taller than the screen.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PagingErrorBar(stringResource(R.string.list_page_error), onRetry = {})
                    PagingErrorBar(ApiError.ServerUnreachable.libraryMessage(), onRetry = {})
                    PagingErrorBar(ApiError.Unexpected.libraryMessage(), onRetry = {})
                    PagingErrorBar(ApiError.ServerFailed.libraryMessage(), onRetry = {})
                    PagingErrorBar(ApiError.NotFound.libraryMessage(), onRetry = {}, canRetry = false)
                }
            }
        }

        val texts = compose.assertAllTextFits()

        // Five messages and four buttons, none of them cut short.
        assertThat(texts).hasSize(9)
        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp", "w700dp-h1000dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 1.3f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
