package app.tfdn.lirelle.feature.player.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stars of a reading already finished: the end reached again soon after, with no new reading, rates
 * that same reading and shows its stars; a reading older than a day never gets them.
 */
@RunWith(AndroidJUnit4::class)
class SameReadingRatingTest {

    private val PlayerHarness.panelStars: Int? get() = controller.state.value.finished?.rating

    private val PlayerHarness.ratings: List<String> get() = reader.calls.filter { it.startsWith("rate") }

    /** Book 42 listened to its end once: the reading from before is 182, the one the end closes is 183. */
    private fun TestScope.finishedOnce(): PlayerHarness = PlayerHarness(this).apply {
        reader.finishedReadingResult = ApiResult.Success(ReadId("182"))
        watched()
        show(SessionBooks.at())
        reader.finishedReadingResult = ApiResult.Success(ReadId("183"))
        show(SessionBooks.finished())
        advanceTimeBy(1.minutes)
    }

    /** What the server says of reading [id]: written [ago] (its end or its rating), with [rating]. */
    private fun PlayerHarness.serverHolds(id: String, ago: Duration, rating: Int?) {
        reader.readingDetails = mapOf(ReadId(id) to FinishedReading(ReadId(id), Instant.now().minus(ago), rating))
    }

    /** The last seconds played again, and the end reached again. */
    private fun TestScope.replayToTheEnd(harness: PlayerHarness) {
        harness.show(SessionBooks.at(chapter = 2, positionMs = 50_000))
        advanceTimeBy(1.minutes)
        harness.show(SessionBooks.finished())
        advanceTimeBy(1.minutes)
    }

    @Test
    fun `the end reached again without a new reading shows its stars, and a change goes to that reading`() =
        runTest {
            // The device: finished, 4 stars on reading 183, the panel set aside, the last seconds replayed.
            val harness = finishedOnce()
            assertThat(harness.panelStars).isNull()
            harness.controller.setRating(4)
            harness.controller.dismissEndPanel()
            advanceTimeBy(1.minutes)
            assertThat(harness.ratings).containsExactly("rate 183 4")
            harness.serverHolds("183", Duration.ofMinutes(2), rating = 4)

            replayToTheEnd(harness)

            // No new reading: the end continues 183, whose stars are shown.
            assertThat(harness.panelStars).isEqualTo(4)
            harness.controller.setRating(5)
            harness.controller.dismissEndPanel()
            advanceTimeBy(1.minutes)
            assertThat(harness.ratings).containsExactly("rate 183 4", "rate 183 5").inOrder()
        }

    @Test
    fun `stars shown and left as they are send nothing`() = runTest {
        val harness = finishedOnce()
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        harness.serverHolds("183", Duration.ofMinutes(2), rating = 4)
        replayToTheEnd(harness)

        harness.controller.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).containsExactly("rate 183 4")
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.CloseBook)
    }

    @Test
    fun `a new listening the server counts as a new reading gets its own stars, empty`() = runTest {
        val harness = finishedOnce()
        harness.controller.setRating(4)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        harness.serverHolds("183", Duration.ofMinutes(2), rating = 4)

        // Listened to again from further back: the end closes a new reading, 190.
        harness.show(SessionBooks.at(chapter = 0, positionMs = 5_000))
        advanceTimeBy(1.minutes)
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("190"))
        harness.show(SessionBooks.finished())
        advanceTimeBy(1.minutes)

        assertThat(harness.panelStars).isNull()
        harness.controller.setRating(3)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        assertThat(harness.ratings).containsExactly("rate 183 4", "rate 190 3").inOrder()
    }

    @Test
    fun `a reading older than a day never gets the stars, the panel says so and offers to try again`() = runTest {
        val harness = PlayerHarness(this)
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("183"))
        harness.serverHolds("183", Duration.ofHours(25), rating = 4)
        harness.watched()
        harness.show(SessionBooks.at())
        advanceTimeBy(1.minutes)
        // The end, but the server records no new reading (the final position was dropped).
        harness.show(SessionBooks.finished())
        advanceTimeBy(1.minutes)

        assertThat(harness.panelStars).isNull()
        harness.controller.setRating(2)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(harness.ratings).isEmpty()
        assertThat(harness.controller.state.value.finished?.failed).isTrue()
    }

    @Test
    fun `the panel opening on a reading already rated shows its stars`() = runTest {
        // The app opened only at the end, on reading 9 finished and rated a moment ago.
        val harness = PlayerHarness(this)
        harness.reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        harness.serverHolds("9", Duration.ofMinutes(5), rating = 5)
        harness.watched()

        harness.show(SessionBooks.finished())
        advanceTimeBy(1.minutes)

        assertThat(harness.panelStars).isEqualTo(5)
        harness.controller.setRating(3)
        harness.controller.dismissEndPanel()
        advanceTimeBy(1.minutes)
        assertThat(harness.ratings).containsExactly("rate 9 3")
    }
}
