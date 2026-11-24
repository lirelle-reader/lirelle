package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The reading the server closed before this one, and the one it closes when the album is finished. */
private val Earlier = ReadId("70")
private val Closed = ReadId("77")

/** The rating given at the end of an album: when it is sent, to which reading, and when it cannot be. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderRatingTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val following = NextVolume(bookId = BookId("15"), title = "Arctic-Nation", seriesIndex = "2")

    private val saved = ApiResult.Success(SavedPosition(SaveOutcome.Saved))

    private fun ComicHarness.rates() = repository.calls.filter { it.startsWith("rate") }

    /** A reader past the last page of an album read to there, which the server then counts as finished. */
    private fun TestScope.atTheEnd(harness: ComicHarness): ComicReaderViewModel {
        harness.repository.finishedReadingResult = ApiResult.Success(Earlier)
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        runCurrent()
        reader.goTo(Pages - 1)
        reader.next()
        harness.repository.finishedReadingResult = ApiResult.Success(Closed)
        return reader
    }

    @Test
    fun `closing with stars rates the reading the server closed, then leaves`() = runTest {
        val harness = ComicHarness(this)
        val reader = atTheEnd(harness)
        reader.setRating(4)

        reader.events.test {
            reader.closeAlbum()

            assertThat(reader.state.value.endPanel?.sending).isTrue()
            expectNoEvents()

            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Closed 4")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
            assertThat(reader.state.value.endPanel).isNull()
        }
        // The book page shows the rating.
        assertThat(harness.changes.version.value).isGreaterThan(0L)
    }

    @Test
    fun `reading the next one with stars rates first, then opens it`() = runTest {
        val harness = ComicHarness(this)
        val reader = atTheEnd(harness)
        reader.setRating(2)

        reader.events.test {
            reader.readNext()
            expectNoEvents()

            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Closed 2")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.OpenNext(BookId("15")))
        }
    }

    @Test
    fun `while the rating is on its way nothing else is asked`() = runTest {
        val harness = ComicHarness(this)
        val reader = atTheEnd(harness)
        reader.setRating(4)

        reader.events.test {
            reader.closeAlbum()
            reader.readNext()
            reader.closeAlbum()
            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Closed 4")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
            expectNoEvents()
        }
    }

    @Test
    fun `the rating waits until the position at the end has reached the server`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val reader = atTheEnd(harness)
        reader.setRating(5)

        reader.events.test {
            reader.closeAlbum()
            advanceTimeBy(3.seconds)

            // The position is still on the device: the server has closed nothing, and is not asked.
            assertThat(harness.outbox.pending().positions).isNotEmpty()
            assertThat(harness.rates()).isEmpty()
            assertThat(reader.state.value.endPanel?.sending).isTrue()
            expectNoEvents()

            // The outbox tries again by itself, 10 s after its last try.
            harness.repository.savePositionResult = saved
            advanceTimeBy(7.seconds + 800.milliseconds)

            assertThat(harness.outbox.pending().positions).isEmpty()
            assertThat(harness.rates()).containsExactly("rate $Closed 5")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
    }

    @Test
    fun `a position that never leaves fails the rating, and trying again asks to close again`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val reader = atTheEnd(harness)
        reader.setRating(5)

        reader.events.test {
            reader.closeAlbum()
            advanceTimeBy(12.seconds)

            assertThat(reader.state.value.endPanel)
                .isEqualTo(ComicEndPanel(rating = 5, nextVolume = following, failed = true))
            assertThat(harness.rates()).isEmpty()
            expectNoEvents()

            harness.repository.savePositionResult = saved
            reader.retryRating()
            assertThat(reader.state.value.endPanel?.sending).isTrue()
            assertThat(reader.state.value.endPanel?.failed).isFalse()
            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Closed 5")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
    }

    @Test
    fun `a rating the server refuses fails, and trying again asks for the next album again`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.rateResult = ApiResult.Failure(ApiError.ServerFailed)
        val reader = atTheEnd(harness)
        reader.setRating(3)

        reader.events.test {
            reader.readNext()
            advanceTimeBy(2.seconds)

            assertThat(reader.state.value.endPanel?.failed).isTrue()
            assertThat(reader.state.value.endPanel?.sending).isFalse()
            expectNoEvents()

            harness.repository.rateResult = ApiResult.Success(Unit)
            reader.retryRating()
            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Closed 3", "rate $Closed 3").inOrder()
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.OpenNext(BookId("15")))
        }
    }

    @Test
    fun `other stars take the failure away, and trying again does nothing without one`() = runTest {
        val harness = ComicHarness(this)
        harness.repository.rateResult = ApiResult.Failure(ApiError.ServerFailed)
        val reader = atTheEnd(harness)

        reader.retryRating()
        assertThat(reader.state.value.endPanel?.sending).isFalse()

        reader.setRating(3)
        reader.closeAlbum()
        advanceTimeBy(2.seconds)
        assertThat(reader.state.value.endPanel?.failed).isTrue()

        reader.setRating(4)
        assertThat(reader.state.value.endPanel?.failed).isFalse()
    }

    @Test
    fun `an album finished before and opened at its end is rated on the reading there was`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = Pages)
        harness.repository.finishedReadingResult = ApiResult.Success(Earlier)
        val reader = harness.reader()
        reader.next()
        reader.setRating(1)

        reader.events.test {
            reader.closeAlbum()
            runCurrent()

            assertThat(harness.rates()).containsExactly("rate $Earlier 1")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
    }

    @Test
    fun `when the server closes no new reading the rating goes to the one before`() = runTest {
        val harness = ComicHarness(this)
        val reader = atTheEnd(harness)
        harness.repository.finishedReadingResult = ApiResult.Success(Earlier)
        reader.setRating(4)

        reader.events.test {
            reader.closeAlbum()
            advanceTimeBy(4.seconds)
            assertThat(harness.rates()).isEmpty()

            advanceTimeBy(2.seconds)

            assertThat(harness.rates()).containsExactly("rate $Earlier 4")
            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
    }

    @Test
    fun `dismissing while the rating is on its way gives it up and stays`() = runTest {
        val harness = ComicHarness(this)
        val reader = atTheEnd(harness)
        reader.setRating(4)

        reader.events.test {
            reader.closeAlbum()
            reader.dismissEndPanel()
            advanceTimeBy(20.seconds)

            assertThat(reader.state.value.endPanel).isNull()
            assertThat(harness.rates()).isEmpty()
            expectNoEvents()

            reader.next()
            assertThat(reader.state.value.endPanel)
                .isEqualTo(ComicEndPanel(rating = 4, nextVolume = following))
        }
    }
}
