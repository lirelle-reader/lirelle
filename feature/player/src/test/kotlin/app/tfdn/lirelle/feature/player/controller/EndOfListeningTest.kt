package app.tfdn.lirelle.feature.player.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.ReadingBaseline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.service.FinishedBook
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Under Robolectric: a rating that throws is logged. */
@RunWith(AndroidJUnit4::class)
class EndOfListeningTest {

    private val next = NextVolume(BookId("43"), "The Long Road Back", "2")
    private val finishedWithNext = PlayerSessionExtras(SessionBooks.book, finished = FinishedBook(next))

    /** Listened to its end: the reading from before is 3, the one the end closed is 9. */
    private fun TestScope.finished(): PlayerHarness = PlayerHarness(this).apply {
        reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        watched()
        show(SessionBooks.at())
        reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        show(SessionBooks.finished(finishedWithNext))
    }

    private val PlayerHarness.finishedState: FinishedState? get() = controller.state.value.finished

    private val PlayerHarness.ratings: List<String> get() = reader.calls.filter { it.startsWith("rate") }

    /** How many times the book was closed from the panel. */
    private fun PlayerHarness.closings(): () -> Int {
        var closed = 0
        scope.launch { controller.closed.collect { closed++ } }
        return { closed }
    }

    @Test
    fun `the stars go to the reading the end closed, then the book is closed`() = runTest {
        val harness = finished()
        val closed = harness.closings()

        harness.controller.setRating(4)
        assertThat(harness.finishedState?.rating).isEqualTo(4)
        harness.controller.closeEndPanel()
        assertThat(harness.finishedState?.sending).isTrue()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4")
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.CloseBook)
        assertThat(closed()).isEqualTo(1)
        assertThat(harness.changes.version.value).isEqualTo(1)
    }

    @Test
    fun `closing without stars sends nothing`() = runTest {
        val harness = finished()
        val closed = harness.closings()

        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).isEmpty()
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.CloseBook)
        assertThat(closed()).isEqualTo(1)
    }

    @Test
    fun `stars are kept between one and five`() = runTest {
        val harness = finished()

        harness.controller.setRating(9)
        assertThat(harness.finishedState?.rating).isEqualTo(5)
        harness.controller.setRating(null)
        assertThat(harness.finishedState?.rating).isNull()
    }

    @Test
    fun `a rating that could not be given keeps the panel, and is given on the next try`() = runTest {
        val harness = finished()
        harness.reader.rateResult = ApiResult.Failure(ApiError.ServerUnreachable)
        harness.controller.setRating(4)

        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.finishedState?.failed).isTrue()
        assertThat(harness.finishedState?.sending).isFalse()
        assertThat(harness.connection.sent).isEmpty()

        harness.reader.rateResult = ApiResult.Success(Unit)
        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4", "rate 9 4")
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.CloseBook)
    }

    @Test
    fun `a rating that throws leaves the panel failed, not sending`() = runTest {
        val harness = PlayerHarness(this)
        harness.readerRepository = object : ReaderRepository by harness.reader {
            override suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit> = error("broken")
        }
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        harness.watched()
        harness.show(SessionBooks.at())
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        harness.show(SessionBooks.finished())
        harness.controller.setRating(4)

        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.finishedState?.sending).isFalse()
        assertThat(harness.finishedState?.failed).isTrue()
    }

    @Test
    fun `the final position still waiting to leave is a failure`() = runTest {
        val harness = finished()
        harness.outbox.enqueue(
            LocalPlayback(BookId("42"), "a2", 60_000, 160_000, Instant.EPOCH, contentVersion = "rev-1"),
        )
        harness.controller.setRating(4)

        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.finishedState?.failed).isTrue()
        assertThat(harness.ratings).isEmpty()
    }

    @Test
    fun `no new reading showing, the stars are not given to the reading from before`() = runTest {
        val harness = finished()
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        harness.controller.setRating(2)

        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).isEmpty()
        assertThat(harness.finishedState?.failed).isTrue()
        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `listened to its end with no screen open, the stars go to the reading the end closed`() = runTest {
        // The process starts from the car's play button: no screen, the flusher writes the positions.
        val harness = PlayerHarness(this)
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        harness.audio.saveResults = listOf(ApiResult.Success(PlaybackWriteResult.Saved(SessionBooks.server(Now))))
        val flusher = harness.flusher()
        harness.outbox.enqueue(LocalPlayback(BookId("42"), "a1", 70_000, 70_000, Now, contentVersion = "rev-1"))
        flusher.kick()
        assertThat(harness.baselines.of(BookId("42"))).isEqualTo(ReadingBaseline(ReadId("3")))
        // The end, in the car: the final position closes reading 9.
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        val end = LocalPlayback(BookId("42"), "a2", 60_000, 160_000, Now.plusSeconds(90), contentVersion = "rev-1")
        harness.outbox.enqueue(end)
        flusher.kick()
        assertThat(harness.outbox.positions).isEmpty()

        // Only then the app is opened: its first look shows the book finished.
        harness.watched()
        harness.show(SessionBooks.finished())
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4")
        assertThat(harness.finishedState?.failed).isFalse()
        // Listening to it again captures a fresh baseline before its first position.
        assertThat(harness.baselines.of(BookId("42"))).isNull()
    }

    @Test
    fun `a book first seen at its end, with no baseline from its positions, has no reading from before to trust`() =
        runTest {
            val harness = PlayerHarness(this)
            harness.reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
            harness.watched()
            harness.show(SessionBooks.finished())
            harness.controller.setRating(4)

            harness.controller.dismissEndPanel()
            advanceTimeBy(1.minutes)

            assertThat(harness.ratings).isEmpty()
            assertThat(harness.finishedState?.failed).isTrue()
        }

    @Test
    fun `dismissed, the panel waits, and the stars already sent are not sent again`() = runTest {
        val harness = finished()
        harness.controller.setRating(4)

        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        assertThat(harness.finishedState?.panelSeen).isTrue()
        assertThat(harness.connection.sent).isEmpty()

        harness.controller.openEndPanel()
        assertThat(harness.finishedState?.panelSeen).isFalse()
        assertThat(harness.finishedState?.rating).isEqualTo(4)

        harness.controller.listenToNext()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4")
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.ListenToNext)
    }

    @Test
    fun `stars changed after a rating go to the same reading at once`() = runTest {
        val harness = finished()
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("12"))
        harness.controller.setRating(5)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4", "rate 9 5")
    }

    @Test
    fun `played again after its end, the next end opens the panel afresh and rates the next reading`() = runTest {
        val harness = finished()
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        // A chapter chosen at the end: a new listening, whose end closes reading 12.
        harness.show(SessionBooks.at(chapter = 2))
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("12"))
        harness.show(SessionBooks.finished())

        assertThat(harness.finishedState?.panelSeen).isFalse()
        assertThat(harness.finishedState?.rating).isNull()
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4", "rate 12 4")
    }

    @Test
    fun `listened to again after an end without stars, the earlier listening's reading is not rated`() = runTest {
        val harness = PlayerHarness(this)
        // The first listening: the flusher captured reading 1 before its first position.
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("1"))
        harness.baselines.capture(BookId("42"))
        harness.watched()
        harness.show(SessionBooks.at())
        // Its end closes reading 2; no stars are given.
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("2"))
        harness.show(SessionBooks.finished())
        harness.controller.dismissEndPanel()

        // Listened to again in the same process: a chapter chosen at the end.
        harness.show(SessionBooks.at(chapter = 2))
        assertThat(harness.baselines.of(BookId("42"))).isNull()
        // Its final position was dropped (written elsewhere meanwhile): it closed nothing.
        harness.show(SessionBooks.finished())
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).isEmpty()
        assertThat(harness.finishedState?.failed).isTrue()
    }

    @Test
    fun `seen before its end, the reading the player saw outranks the flusher's baseline`() = runTest {
        val harness = PlayerHarness(this)
        // A baseline left from an earlier listening of this process.
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("1"))
        harness.baselines.capture(BookId("42"))
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("2"))
        harness.watched()
        harness.show(SessionBooks.at())
        harness.show(SessionBooks.finished())
        harness.controller.setRating(4)

        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).isEmpty()
        assertThat(harness.finishedState?.failed).isTrue()
    }

    @Test
    fun `seen before its end but unable to ask, the flusher's baseline is used`() = runTest {
        val harness = PlayerHarness(this)
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        harness.baselines.capture(BookId("42"))
        harness.reader.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        harness.watched()
        harness.show(SessionBooks.at())
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        harness.show(SessionBooks.finished())
        harness.controller.setRating(4)

        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 9 4")
    }

    @Test
    fun `closing the panel without stars ends the listening, and its baseline`() = runTest {
        val harness = finished()
        harness.baselines.capture(BookId("42"))

        harness.controller.closeEndPanel()

        assertThat(harness.baselines.of(BookId("42"))).isNull()
    }

    @Test
    fun `the next volume ends the listening, and its baseline`() = runTest {
        val harness = finished()
        harness.baselines.capture(BookId("42"))

        harness.controller.listenToNext()

        assertThat(harness.baselines.of(BookId("42"))).isNull()
    }

    @Test
    fun `a moment without a book keeps the panel as it was left`() = runTest {
        val harness = finished()
        harness.controller.setRating(3)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        harness.controller.setRating(4)

        harness.show(SessionSnapshot())
        harness.show(SessionBooks.finished(finishedWithNext))

        assertThat(harness.finishedState?.rating).isEqualTo(4)
        assertThat(harness.finishedState?.panelSeen).isTrue()
        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)
        assertThat(harness.ratings).containsExactly("rate 9 3", "rate 9 4")
    }

    @Test
    fun `another book starts afresh`() = runTest {
        val harness = finished()
        harness.controller.setRating(3)

        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))
        harness.show(SessionBooks.finished(PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))

        assertThat(harness.finishedState?.rating).isNull()
    }

    @Test
    fun `the panel's actions do nothing before the end`() = runTest {
        val harness = PlayerHarness(this)
        harness.watched()
        harness.show(SessionBooks.at())

        harness.controller.closeEndPanel()
        harness.controller.listenToNext()
        advanceTimeBy(1.minutes)

        assertThat(harness.connection.sent).isEmpty()
    }
}
