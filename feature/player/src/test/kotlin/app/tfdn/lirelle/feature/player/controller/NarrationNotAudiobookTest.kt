package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A narration shows its book in the session, but it is not that book's audiobook: no end panel nor rating, no
 * newer position of the audiobook, and the player's state says it is a narration.
 */
class NarrationNotAudiobookTest {

    private val earlier = Instant.parse("2026-11-15T08:00:00Z")
    private val later = Instant.parse("2026-11-15T10:00:00Z")

    private val offered = OfferedListening(120_000, later, OfferedChapter(3, "Closing", 20_000))

    /** The narration of book 42, at 2:00 on a timeline like the audiobook's: where the audiobook's offer is. */
    private val narrationAtOffer = SessionBooks.at(
        chapter = 2,
        positionMs = 20_000,
        extras = PlayerSessionExtras(SessionBooks.book, narrationFileId = FileId("1")),
    )

    @Test
    fun `the player's state says a narration is loaded, with no end panel nor newer position`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()

        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(narrationFinished = true)))

        assertThat(state.value.bookId).isEqualTo(BookId("42"))
        assertThat(state.value.narrating).isTrue()
        assertThat(state.value.finished).isNull()
        assertThat(state.value.newerPosition).isNull()

        harness.show(SessionBooks.at())
        assertThat(state.value.narrating).isFalse()
    }

    @Test
    fun `a narration's end opens no end panel and looks for no reading to rate, an audiobook's still does`() =
        runTest {
            val harness = PlayerHarness(this)
            val state = harness.watched()

            harness.show(SessionNarrations.at())
            harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(narrationFinished = true)))
            advanceTimeBy(1.seconds)

            assertThat(state.value.finished).isNull()
            assertThat(harness.reader.calls).isEmpty()

            harness.show(SessionBooks.at())
            harness.show(SessionBooks.finished())
            assertThat(state.value.finished).isNotNull()
        }

    @Test
    fun `back at the front during a narration, no newer position of the audiobook is looked for`() = runTest {
        val harness = PlayerHarness(this)
        harness.watched()
        harness.show(SessionNarrations.at())

        harness.controller.checkNewerPosition()
        advanceTimeBy(1.seconds)

        assertThat(harness.audio.calls.filter { it.startsWith("playbackState") }).isEmpty()
    }

    @Test
    fun `the audiobook's offer is neither shown, followed nor taken during the book's narration`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
        harness.conflicts.written(ownWrite(earlier), acknowledged = true)
        val state = harness.watched()
        harness.controller.checkNewerPosition()
        assertThat(state.value.newerPosition).isEqualTo(offered)

        // At the very place offered: an audiobook would follow it without a word.
        harness.show(narrationAtOffer)
        val taken = harness.controller.acceptNewerPosition()
        harness.controller.ignoreNewerPosition()

        assertThat(state.value.newerPosition).isNull()
        assertThat(taken).isFalse()
        assertThat(harness.connection.sent.filterIsInstance<PlayerCommand.SeekBookTime>()).isEmpty()
        // Back to the audiobook, the offer is still there.
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    @Test
    fun `from a book's narration to its audiobook, back at the front checks the audiobook once it shows`() =
        runTest {
            val harness = PlayerHarness(this)
            harness.watched()
            harness.show(SessionNarrations.at())
            harness.controller.load(BookId("42"))

            harness.controller.checkNewerPosition()
            advanceTimeBy(1.seconds)
            assertThat(harness.audio.calls.filter { it.startsWith("playbackState") }).isEmpty()

            harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book, loading = true)))
            harness.show(SessionBooks.at())
            advanceTimeBy(1.seconds)

            assertThat(harness.audio.calls.filter { it.startsWith("playbackState") })
                .containsExactly("playbackState 42")
        }

    @Test
    fun `an audiobook's offer is dropped when its narration is followed by another audiobook`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
        harness.conflicts.written(ownWrite(earlier), acknowledged = true)
        val state = harness.watched()
        harness.controller.checkNewerPosition()
        assertThat(state.value.newerPosition).isEqualTo(offered)

        harness.show(SessionNarrations.at())
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `an audiobook's offer stays when its narration is followed by the same audiobook`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
        harness.conflicts.written(ownWrite(earlier), acknowledged = true)
        val state = harness.watched()
        harness.controller.checkNewerPosition()

        harness.show(SessionNarrations.at())
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))

        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    private fun ownWrite(at: Instant) =
        LocalPlayback(BookId("42"), "a1", 70_000, 70_000, at, operationId = "op-$at", contentVersion = "rev-1")
}
