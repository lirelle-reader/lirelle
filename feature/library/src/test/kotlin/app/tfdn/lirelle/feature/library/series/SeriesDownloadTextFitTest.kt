package app.tfdn.lirelle.feature.library.series

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.library.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the download of a series overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SeriesDownloadTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val width = if (screen == Tablet) WindowWidthClass.Expanded else WindowWidthClass.Compact

    private val longName = "A series whose name is far too long for one line of a sheet"

    @Test
    fun `the button and its line`() {
        var state by mutableStateOf(SeriesDownloadUiState())
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    // As in the page, which keeps this margin around its content.
                    SeriesDownloadBlock(state, onClick = {}, modifier = Modifier.padding(16.dp))
                }
            }
        }

        val lines = listOf(
            SeriesDownloadUiState(),
            SeriesDownloadUiState(onDevice = SeriesOnDevice(books = 1, of = 120, bytes = 1_300 * Mb)),
            SeriesDownloadUiState(onDevice = SeriesOnDevice(books = 87, of = 120, bytes = 41_300 * Mb)),
            SeriesDownloadUiState(onDevice = SeriesOnDevice(books = 120, of = 120, bytes = 141_300 * Mb)),
            SeriesDownloadUiState(allowed = false),
        )
        lines.forEach {
            state = it
            assertNothingCutShort()
        }
    }

    @Test
    fun `the sheet, with everything it can say`() {
        var sheet by mutableStateOf(sampleSheet())
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    // A sheet for each state: what was chosen in one is not carried into the next.
                    key(sheet) {
                        SeriesDownloadSheetContent(sheet, onCancel = {}, onRetry = {}, onConfirm = { _, _ -> })
                    }
                }
            }
        }

        val sheets = listOf(
            sampleSheet(name = longName, chosen = DownloadFormat.entries.toSet(), freeBytes = Mb, waitsForWifi = true),
            sampleSheet(chosen = emptySet()),
            sampleSheet(offers = Loadable.Ready(MockOffers.takeLast(1))),
            sampleSheet(offers = Loadable.Ready(pagedOffers(TextName.Djvu, TextName.Djvu))),
            sampleSheet(offers = Loadable.Ready(pagedOffers(TextName.Pdf, TextName.Cb7))),
            sampleSheet(offers = Loadable.Loading),
            sampleSheet(offers = Loadable.Failed(ApiError.ServerFailed)),
        )
        sheets.forEach {
            sheet = it
            assertNothingCutShort()
        }
    }

    /** Every text fits, and what is not the label of a button is whole: it wraps instead of ending short. */
    private fun assertNothingCutShort() {
        val cancel = compose.activity.getString(R.string.series_download_cancel)
        compose.assertAllTextFits()
            .filterNot { it.isLabel && it.text == cancel }
            .forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Tablet = "w1280dp-h800dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Tablet).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
