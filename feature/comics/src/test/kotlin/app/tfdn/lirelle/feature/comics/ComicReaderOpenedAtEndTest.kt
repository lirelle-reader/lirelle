package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The reading the server closes when the album is finished. */
private val Closed = ReadId("77")

/**
 * An album that opens on the spread of its last page: no page is turned before the end, so nothing wrote the
 * position there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderOpenedAtEndTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `turning past the only page of an album writes its position at the end`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(1)
        val reader = harness.reader()
        runCurrent()
        assertThat(harness.store.positions[AlbumFile]).isNull()

        reader.next()
        runCurrent()

        assertThat(reader.state.value.endPanel).isNotNull()
        val sent = harness.repository.savedPositions.single()
        assertThat(sent.page).isEqualTo(1)
        assertThat(sent.percent).isEqualTo(100.0)
    }

    @Test
    fun `an album of one page can be rated`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(1)
        val reader = harness.reader()
        runCurrent()
        reader.next()
        harness.repository.finishedReadingResult = ApiResult.Success(Closed)
        reader.setRating(4)

        reader.events.test {
            reader.closeAlbum()
            advanceTimeBy(2.seconds)

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
        // The server closes a reading on the position at the end: it has it before it is asked for the reading.
        val asked = harness.repository.calls.filter { it.startsWith("savePosition") || it.startsWith("rate") }
        assertThat(asked).containsExactly("savePosition $AlbumFile", "rate $Closed 4").inOrder()
    }

    @Test
    fun `an album opened in two pages on its last spread writes the end when it is turned past`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(Pages - 1)
        harness.serverHas(page = Pages - 2)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        runCurrent()
        assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 3, Pages - 2))
        assertThat(harness.repository.savedPositions).isEmpty()

        reader.next()
        runCurrent()

        val sent = harness.repository.savedPositions.single()
        assertThat(sent.page).isEqualTo(Pages - 2)
        assertThat(sent.percent).isEqualTo(100.0)
    }

    @Test
    fun `a page turned to that the window then pairs with the last one is at the end when turned past`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(Pages - 1)
        val reader = harness.reader()
        reader.goTo(Pages - 3)
        reader.windowSize(widthDp = 1280, heightDp = 800)
        assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 3, Pages - 2))

        reader.next()
        runCurrent()

        assertThat(reader.state.value.endPanel).isNotNull()
        assertThat(harness.repository.savedPositions.last().percent).isEqualTo(100.0)
    }

    @Test
    fun `the end is written once, however many times the panel opens`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(1)
        val reader = harness.reader()
        reader.next()
        reader.dismissEndPanel()

        reader.next()
        advanceTimeBy(5.seconds)

        assertThat(harness.repository.savedPositions).hasSize(1)
    }

    @Test
    fun `a last page reached by turning is not written again by the panel`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.goTo(Pages - 1)

        reader.next()
        advanceTimeBy(5.seconds)

        assertThat(harness.repository.savedPositions).hasSize(1)
    }
}
