package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The panel at the end of an album: when it opens, the next album it offers, and how it is left. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderEndTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val following = NextVolume(bookId = BookId("15"), title = "Arctic-Nation", seriesIndex = "2")

    @Test
    fun `the panel is closed until the reader turns past the last page`() = runTest {
        val reader = ComicHarness(this).reader()
        assertThat(reader.state.value.endPanel).isNull()

        reader.goTo(Pages - 1)
        assertThat(reader.state.value.endPanel).isNull()

        reader.events.test {
            reader.next()

            assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel())
            assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 1))
            assertThat(reader.state.value.barsShown).isFalse()
            expectNoEvents()
        }
    }

    @Test
    fun `with two pages too, past the last spread`() = runTest {
        val reader = ComicHarness(this).reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.goTo(Pages - 2)
        assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 3, Pages - 2))

        reader.next()
        assertThat(reader.state.value.endPanel).isNull()
        reader.next()

        assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 1))
        assertThat(reader.state.value.endPanel).isNotNull()
    }

    @Test
    fun `turning past the last page sends the position at the end at once`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        runCurrent()
        assertThat(harness.repository.savedPositions).isEmpty()

        reader.next()
        runCurrent()

        val sent = harness.repository.savedPositions.single()
        assertThat(sent.page).isEqualTo(Pages)
        assertThat(sent.percent).isEqualTo(100.0)
    }

    @Test
    fun `the next album of the series is asked among the comics, and shown`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        assertThat(harness.repository.nextVolumeGroups).isEmpty()

        reader.next()

        assertThat(harness.repository.calls).contains("nextVolume 4 $AlbumBook")
        assertThat(harness.repository.nextVolumeGroups).containsExactly(VolumeGroup.Comics)
        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(nextVolume = following))
    }

    @Test
    fun `an album without a series has no next one, and none is asked`() = runTest {
        val harness = ComicHarness(this, detail = sampleComicDetail(series = null))
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        reader.goTo(Pages - 1)

        reader.next()

        assertThat(harness.repository.nextVolumeGroups).isEmpty()
        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel())
    }

    @Test
    fun `a series without a next album, or a server that does not say, shows no row`() = runTest {
        val last = ComicHarness(this)
        val lastReader = last.reader()
        lastReader.goTo(Pages - 1)
        lastReader.next()
        assertThat(last.repository.nextVolumeGroups).hasSize(1)
        assertThat(lastReader.state.value.endPanel).isEqualTo(ComicEndPanel())

        val away = ComicHarness(this)
        away.repository.nextVolumeResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val awayReader = away.reader()
        awayReader.goTo(Pages - 1)
        awayReader.next()
        assertThat(awayReader.state.value.endPanel).isEqualTo(ComicEndPanel())
    }

    @Test
    fun `a next album the server did not say is asked again when the panel comes back`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.nextVolumeResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        reader.next()
        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel())
        reader.dismissEndPanel()

        harness.repository.nextVolumeResult = ApiResult.Success(following)
        reader.next()

        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(nextVolume = following))
        assertThat(harness.repository.nextVolumeGroups).hasSize(2)
    }

    @Test
    fun `a series said to have no next album is not asked again`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        reader.next()
        reader.dismissEndPanel()

        reader.next()

        assertThat(harness.repository.nextVolumeGroups).hasSize(1)
    }

    @Test
    fun `the next album is asked once while the server has not answered`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        runCurrent()
        val slow = CompletableDeferred<Unit>()
        harness.repository.gate = slow
        reader.next()
        reader.dismissEndPanel()

        reader.next()
        slow.complete(Unit)
        runCurrent()

        assertThat(harness.repository.nextVolumeGroups).hasSize(1)
        assertThat(reader.state.value.endPanel?.nextVolume).isEqualTo(following)
    }

    @Test
    fun `without stars, closing the album leaves at once and sends no rating`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.clocks.advance(40.seconds)
        reader.goTo(Pages - 1)
        reader.next()

        reader.events.test {
            reader.closeAlbum()

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
            assertThat(reader.state.value.endPanel).isNull()
        }
        assertThat(harness.repository.calls.none { it.startsWith("rate") }).isTrue()
        // What was read is written, as when back leaves.
        runCurrent()
        val session = harness.repository.savedSessions.single().second
        assertThat(session.activeSeconds).isEqualTo(40)
        assertThat(session.endPercent).isEqualTo(100.0)
    }

    @Test
    fun `without stars, reading the next one opens it at once`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        reader.next()

        reader.events.test {
            reader.readNext()

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.OpenNext(BookId("15")))
            assertThat(reader.state.value.endPanel).isNull()
        }
        assertThat(harness.repository.calls.none { it.startsWith("rate") }).isTrue()
    }

    @Test
    fun `reading the next one does nothing when there is none`() = runTest {
        val reader = ComicHarness(this).reader()
        reader.goTo(Pages - 1)
        reader.next()

        reader.events.test {
            reader.readNext()

            expectNoEvents()
            assertThat(reader.state.value.endPanel).isNotNull()
        }
    }

    @Test
    fun `dismissed, the panel leaves the reader on the last page, and comes back as it was left`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        reader.goTo(Pages - 1)
        reader.next()
        reader.setRating(4)

        reader.events.test {
            reader.dismissEndPanel()

            assertThat(reader.state.value.endPanel).isNull()
            assertThat(reader.state.value.spread).isEqualTo(Spread(Pages - 1))
            expectNoEvents()

            reader.next()

            assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(rating = 4, nextVolume = following))
            expectNoEvents()
        }
        // The next album was asked once, and no rating was sent.
        assertThat(harness.repository.nextVolumeGroups).hasSize(1)
        assertThat(harness.repository.calls.none { it.startsWith("rate") }).isTrue()
    }

    @Test
    fun `the stars are from one to five, and can be taken away`() = runTest {
        val reader = ComicHarness(this).reader()
        reader.setRating(3)
        assertThat(reader.state.value.endPanel).isNull()
        reader.goTo(Pages - 1)
        reader.next()

        reader.setRating(3)
        assertThat(reader.state.value.endPanel?.rating).isEqualTo(3)
        reader.setRating(9)
        assertThat(reader.state.value.endPanel?.rating).isEqualTo(5)
        reader.setRating(null)
        assertThat(reader.state.value.endPanel?.rating).isNull()
    }
}
