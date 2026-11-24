package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.FilePlaces
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val Pages = 60
private const val PageBytes = FakeFetcher.UnknownSize

/** The places a run may look at for each page, whatever the album: a few, not one per page of the album. */
private const val LooksPerPage = 12

/** Counts the places asked for: a run looks at a file each time it asks where it is. */
private class CountingPlaces(private val places: FilePlaces) : FilePlaces {
    var asked = 0
        private set

    override val folders get() = places.folders

    override fun file(bookId: BookId, format: DownloadFormat, name: String): File {
        asked++
        return places.file(bookId, format, name)
    }

    override fun freeBytes(): Long = Long.MAX_VALUE
}

/** What an album costs to fetch grows with its pages, not with their square. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedCostTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `each page of an album costs a few looks at the device, however many pages it has`() = runTest {
        val places = CountingPlaces(DownloadFiles(folder.newFolder("downloads")))
        val plan = album(Pages)
        val states = mutableListOf<Pair<FormatState, Int>>()
        val book = BookId("1")
        val sizes = FormatSizes(places, book, DownloadFormat.Text, plan.files, plan.expectedBytes)
        val batch = Batch(book, DownloadFormat.Text, plan.files, emptySet(), sizes) { state, pages ->
            states += state to pages
        }
        val run = FileRun(
            places,
            FormatSources(FakePlans(), FakeFetcher(places), emptySet()),
            StandardTestDispatcher(testScheduler),
        )

        val result = run.run(batch)

        assertThat(result).isEqualTo(StepResult.Done)
        assertThat(run.fetched).isEqualTo(Pages)
        // What it says is what it said: the bytes on the device against the estimate, page after page.
        assertThat(states.first()).isEqualTo(FormatState.Downloading(0, AlbumBytes) to 0)
        assertThat(states.last()).isEqualTo(FormatState.Downloading(Pages * PageBytes, Pages * PageBytes) to Pages)
        assertThat(states.map { it.second }.distinct()).isEqualTo((0..Pages).toList())
        assertThat(places.asked).isAtMost(LooksPerPage * Pages)
    }
}
