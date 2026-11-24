package app.tfdn.lirelle.feature.library.series

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.library.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The series page: the button that downloads the series, its line, and the sheet it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SeriesDownloadPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val library = FakeLibraryRepository()
    private val repository = SeriesRepository(library)
    private val downloads = FakeSeriesDownloads()
    private val canDownload = MutableStateFlow(true)
    private val books = listOf(sampleBook("1"), sampleBook("2", readState = ReadState.Read), sampleBook("3"))

    private fun show() {
        library.seriesBooksResult = ApiResult.Success(SeriesBooks(sampleSeries("4", name = "Dawn", books = 3), books))
        books.forEach { repository.details[it.id] = ApiResult.Success(sampleBookDetail(id = it.id.value)) }
        val network = MutableStateFlow(NetworkState(online = true, unmetered = true))
        val surroundings = DownloadSurroundings(network, canDownload, FakeSeriesSettings()) { 41 * Gb }
        val download = SeriesDownloadViewModel(repository, downloads, surroundings, FakeKeptData(downloads.calls))
        val viewModel = SeriesViewModel("4", library, InMemoryLibraryPreferences())
        compose.setContent {
            LirelleTheme {
                SeriesScreen(SeriesId("4"), onBack = {}, onOpenBook = {}, viewModel = viewModel, downloads = download)
            }
        }
    }

    private fun button() = compose.onNodeWithTag(SeriesDownloadButtonTag)

    private fun line() = compose.onNodeWithTag(SeriesDownloadLineTag)

    @Test
    fun `the button is under the heading, without a line while nothing is kept`() {
        show()

        button().assertIsEnabled()
        compose.onAllNodesWithTag(SeriesDownloadLineTag).assertCountEquals(0)
        compose.onAllNodesWithTag(SeriesSheetTag).assertCountEquals(0)
    }

    @Test
    fun `the line says how many books are on the device, and when all are`() {
        show()

        downloads.kept.value = listOf(
            downloaded("1", record(Text, FormatState.Complete(700 * Mb))),
            downloaded("3", record(Text, FormatState.Complete(600 * Mb))),
        )
        line().assertTextEquals("2 of 3 books are on the device · ${context.fileSize(1_300 * Mb)}")

        downloads.kept.value = downloads.kept.value.take(1)
        line().assertTextEquals("1 of 3 books is on the device · ${context.fileSize(700 * Mb)}")

        downloads.kept.value = books.map { downloaded(it.id.value, record(Text, FormatState.Complete(Gb))) }
        line().assertTextEquals("All 3 books are on the device · ${context.fileSize(3 * Gb)}")
        button().assertIsEnabled()
    }

    @Test
    fun `an account that may not download has the button disabled, and the line says why`() {
        canDownload.value = false
        show()

        button().assertIsNotEnabled()
        line().assertTextEquals(context.getString(R.string.series_download_not_allowed))
    }

    @Test
    fun `the button opens the sheet, and confirming asks for the books that are not finished`() {
        show()

        button().performClick()
        compose.onNodeWithTag(SeriesSheetTag).assertExists()
        assertThat(repository.asked).containsExactly(BookId("1"), BookId("2"), BookId("3")).inOrder()

        compose.onNodeWithTag(SeriesSheetConfirmTag).performScrollTo().performClick()

        assertThat(downloads.calls).containsExactly("ask 1 Text", "ask 3 Text", "keep 1", "keep 3").inOrder()
        compose.onAllNodesWithTag(SeriesSheetTag).assertCountEquals(0)
    }
}
