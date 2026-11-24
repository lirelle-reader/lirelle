package app.tfdn.lirelle.feature.book

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val TextContrast = 4.5f

/** Enough for the chip to be told from the page it lies on. */
private const val GroundContrast = 1.5f

/** The contrast of two colours, as the accessibility guidelines count it: from 1 to 21. */
private fun contrast(one: Color, other: Color): Float {
    val (dark, light) = listOf(one.luminance(), other.luminance()).sorted()
    return (light + 0.05f) / (dark + 0.05f)
}

/** The chip of a page built from what the device keeps: in the middle of the page, and standing out from it. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class OfflineChipTest(private val language: String, private val noNetwork: Boolean) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = BookUiState(
        book = Loadable.Ready(sampleBookDetail()),
        reading = BookReading(11, lastReadAt = null, totalSeconds = 0, sessionCount = 0),
        listening = Loadable.Failed(if (noNetwork) ApiError.ServerUnreachable else ApiError.NotFound),
        offline = true,
        noNetwork = noNetwork,
        keptFormats = setOf(DownloadFormat.Text),
    )

    @Test
    fun `the chip is in the middle of the page`() {
        compose.setContent {
            LirelleTheme {
                BookContent(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onRefresh = {},
                    onOpenBook = {},
                    onOpenAuthor = {},
                    onOpenGenre = {},
                    onRead = { _, _ -> },
                    onListen = { _, _ -> },
                    onNarrate = {},
                )
            }
        }

        val page = compose.onRoot().getUnclippedBoundsInRoot()
        val chip = compose.onNodeWithTag(if (noNetwork) OfflineChipTag else GoneChipTag).getUnclippedBoundsInRoot()

        val middle = (chip.left + chip.right) / 2
        assertThat(middle.value).isWithin(1f).of((page.width / 2).value)
        // As wide as what it says, not as the page.
        assertThat(chip.width).isLessThan(page.width - 64.dp)
    }

    @Test
    fun `the chip stands out from the page, and what it says from the chip, in both themes`() {
        val seen = mutableMapOf<String, Pair<OfflineChipColors, Color>>()
        compose.setContent {
            mapOf("dark" to true, "light" to false).forEach { (theme, dark) ->
                LirelleTheme(darkTheme = dark) {
                    seen[theme] = offlineChipColors() to MaterialTheme.colorScheme.background
                }
            }
        }
        compose.waitForIdle()

        assertThat(seen.keys).containsExactly("dark", "light")
        seen.forEach { (theme, colors) ->
            val (chip, page) = colors
            assertWithMessage("ink on the chip, $theme").that(contrast(chip.ink, chip.ground)).isAtLeast(TextContrast)
            assertWithMessage("chip on the page, $theme").that(contrast(chip.ground, page)).isAtLeast(GroundContrast)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} no network: {1}")
        fun cases(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language -> listOf(true, false).map { arrayOf<Any>(language, it) } }
    }
}
