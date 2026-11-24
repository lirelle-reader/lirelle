package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NewerPositionTest {

    private val earlier = Instant.parse("2026-11-15T08:00:00Z")
    private val later = Instant.parse("2026-11-15T10:00:00Z")

    /** The player at 1:10, the second chapter; the server holds 2:00, written elsewhere at [later]. */
    private fun TestScope.listening(): PlayerHarness = PlayerHarness(this).apply {
        show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
    }

    /** A position of this device at 1:10 (or [assetId], [positionMs]), made at [at]. */
    private fun ownWrite(at: Instant, assetId: String = "a1", positionMs: Long = 70_000) =
        LocalPlayback(BookId("42"), assetId, positionMs, if (assetId == "a1") positionMs else 100_000 + positionMs, at,
            operationId = "op-$at", contentVersion = "rev-1")

    private val closing = OfferedChapter(3, "Closing", 20_000)
    private val offered = OfferedListening(120_000, later, closing)

    @Test
    fun `back at the front, a state newer than the last write and elsewhere is offered`() = runTest {
        val harness = listening()
        harness.conflicts.written(ownWrite(earlier), acknowledged = true)
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    @Test
    fun `a book just asked for is checked once it shows, not the one of before`() = runTest {
        val harness = listening()
        harness.watched()
        harness.controller.load(BookId("43"))

        harness.controller.checkNewerPosition()
        advanceTimeBy(1.seconds)
        assertThat(harness.audio.calls.filter { it.startsWith("playbackState") }).isEmpty()

        val other = SessionBooks.book.copy(id = BookId("43"))
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(other, loading = true)))
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(other)))
        advanceTimeBy(1.seconds)

        assertThat(harness.audio.calls.filter { it.startsWith("playbackState") }).containsExactly("playbackState 43")
    }

    @Test
    fun `back at the front, a position stored after the state does not hide it when the last write is older`() =
        runTest {
            val harness = listening()
            // A tick stored at 10:01 whose write was dropped; the last write the server took was at 8:00.
            harness.store.put(ownWrite(later.plusSeconds(60)))
            harness.conflicts.written(ownWrite(earlier), acknowledged = true)
            val state = harness.watched()

            harness.controller.checkNewerPosition()

            assertThat(state.value.newerPosition).isEqualTo(offered)
        }

    @Test
    fun `a state older than the last write the server took is not offered`() = runTest {
        val harness = listening()
        harness.conflicts.written(ownWrite(later.plusSeconds(60)), acknowledged = true)
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `a state older than a write that may have been stored is not offered`() = runTest {
        val harness = listening()
        harness.conflicts.written(ownWrite(later.plusSeconds(60)), acknowledged = false)
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `a state older than a position still waiting to be sent is not offered`() = runTest {
        val harness = listening()
        harness.outbox.enqueue(ownWrite(later.plusSeconds(60)))
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `a state in the book's known version is this device's own, even after a restart`() = runTest {
        // Nothing of this process wrote: the known version is what remains of the last write.
        val harness = listening()
        harness.store.setKnownVersion(BookId("42"), "v7")
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `a state at the same place is not offered`() = runTest {
        val harness = listening()
        harness.audio.playbackStateResult =
            ApiResult.Success(SessionBooks.server(later, assetId = "a1", positionMs = 72_000))
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `the player's own write given back is not offered`() = runTest {
        val harness = listening()
        harness.conflicts.written(ownWrite(earlier, assetId = "a2", positionMs = 20_000), acknowledged = true)
        val state = harness.watched()

        harness.controller.checkNewerPosition()

        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `a check waits for the session to show the book`() = runTest {
        val harness = PlayerHarness(this)
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
        val state = harness.watched()

        harness.controller.checkNewerPosition()
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book, loading = true)))
        advanceTimeBy(1.seconds)
        assertThat(harness.audio.calls).doesNotContain("playbackState 42")

        harness.show(SessionBooks.at())

        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    @Test
    fun `a conflict is offered, even when the device stored a later position`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60))
        harness.store.put(dropped)
        val state = harness.watched()

        harness.conflicts.dropped(dropped, SessionBooks.server(later))

        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    @Test
    fun `a conflict met while no screen was open is offered when one opens`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60))
        harness.store.put(dropped)
        harness.conflicts.dropped(dropped, SessionBooks.server(later))

        val state = harness.watched()

        assertThat(state.value.newerPosition).isEqualTo(offered)
        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `a conflict whose state could not be read is read again`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60))
        harness.store.put(dropped)
        val state = harness.watched()

        harness.conflicts.dropped(dropped, server = null)

        assertThat(state.value.newerPosition).isEqualTo(offered)
    }

    @Test
    fun `a conflict at the same place as the device is followed without a word`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60), assetId = "a2", positionMs = 21_000)
        harness.store.put(dropped)
        val state = harness.watched()

        harness.conflicts.dropped(dropped, SessionBooks.server(later))

        assertThat(state.value.newerPosition).isNull()
        assertThat(harness.store.versions[BookId("42")]).isEqualTo("v7")
        assertThat(harness.conflicts.standing.value).isEmpty()
    }

    @Test
    fun `going there makes the server's version known, then seeks`() = runTest {
        val harness = listening()
        val state = harness.watched()
        harness.controller.checkNewerPosition()

        assertThat(harness.controller.acceptNewerPosition()).isTrue()

        assertThat(harness.store.versions[BookId("42")]).isEqualTo("v7")
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.SeekBookTime(120_000))
        assertThat(state.value.newerPosition).isNull()

        harness.controller.checkNewerPosition()
        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `going there ends the conflict`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60))
        harness.store.put(dropped)
        harness.watched()
        harness.conflicts.dropped(dropped, SessionBooks.server(later))

        harness.controller.acceptNewerPosition()

        assertThat(harness.conflicts.standing.value).isEmpty()
        assertThat(harness.outbox.positions).isEmpty()
    }

    @Test
    fun `ignoring writes the device's position at once, over the server's version`() = runTest {
        val harness = listening()
        val stored = ownWrite(later.plusSeconds(60))
        harness.store.put(stored)
        val state = harness.watched()
        harness.conflicts.dropped(stored, SessionBooks.server(later))

        harness.controller.ignoreNewerPosition()

        assertThat(harness.store.versions[BookId("42")]).isEqualTo("v7")
        val written = harness.outbox.positions.single()
        assertThat(written.baseVersion).isEqualTo("v7")
        assertThat(written.bookTimeMs).isEqualTo(70_000)
        assertThat(written.at).isEqualTo(Now)
        assertThat(harness.kicks).isEqualTo(1)
        assertThat(harness.connection.sent).isEmpty()
        assertThat(state.value.newerPosition).isNull()
        assertThat(harness.conflicts.standing.value).isEmpty()
    }

    @Test
    fun `an ignored state is not offered again, a newer one is`() = runTest {
        val harness = listening()
        val state = harness.watched()
        harness.controller.checkNewerPosition()
        harness.controller.ignoreNewerPosition()

        harness.controller.checkNewerPosition()
        assertThat(state.value.newerPosition).isNull()

        harness.audio.playbackStateResult =
            ApiResult.Success(SessionBooks.server(later.plusSeconds(600), version = "v8"))
        harness.controller.checkNewerPosition()
        assertThat(state.value.newerPosition).isEqualTo(OfferedListening(120_000, later.plusSeconds(600), closing))
    }

    @Test
    fun `listening on for two minutes without an answer keeps the device's position, and the offer goes`() =
        runTest {
            val harness = listening()
            val state = harness.watched()

            val dropped = ownWrite(later.plusSeconds(60))
            harness.store.put(dropped)
            harness.conflicts.dropped(dropped, SessionBooks.server(later))
            // The player's ticks while the offer waits: two minutes of listening.
            (0L..120L step 5).forEach { seconds ->
                harness.store.put(ownWrite(later.plusSeconds(60 + seconds), positionMs = 70_000 + seconds * 1_000))
                harness.conflicts.ticked(BookId("42"), seconds * 1_000)
                if (seconds == 60L) assertThat(state.value.newerPosition).isEqualTo(offered)
            }

            assertThat(state.value.newerPosition).isNull()
            assertThat(harness.outbox.positions.single().baseVersion).isEqualTo("v7")

            harness.controller.checkNewerPosition()
            assertThat(state.value.newerPosition).isNull()
        }

    @Test
    fun `an offer of the place playback is at already is followed without a word`() = runTest {
        val harness = listening()
        val dropped = ownWrite(later.plusSeconds(60))
        harness.store.put(dropped)
        val state = harness.watched()
        harness.conflicts.dropped(dropped, SessionBooks.server(later))
        assertThat(state.value.newerPosition).isEqualTo(offered)

        // Playback is at 2:01, a second from the offered 2:00.
        harness.show(SessionBooks.at(chapter = 2, positionMs = 21_000))

        assertThat(state.value.newerPosition).isNull()
        assertThat(harness.store.versions[BookId("42")]).isEqualTo("v7")
        assertThat(harness.conflicts.standing.value).isEmpty()
        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `nothing to go to without an offer`() = runTest {
        val harness = listening()

        assertThat(harness.controller.acceptNewerPosition()).isFalse()
        harness.controller.ignoreNewerPosition()

        assertThat(harness.connection.sent).isEmpty()
        assertThat(harness.store.versions).isEmpty()
    }

    @Test
    fun `another book drops the offer`() = runTest {
        val harness = listening()
        val state = harness.watched()
        harness.controller.checkNewerPosition()

        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))

        assertThat(state.value.newerPosition).isNull()
        harness.show(SessionBooks.at())
        assertThat(state.value.newerPosition).isNull()
    }

    @Test
    fun `the timeline is read again after the book's files changed`() = runTest {
        val harness = listening()
        harness.watched()
        harness.controller.checkNewerPosition()
        harness.controller.checkNewerPosition()
        assertThat(harness.audio.calls.count { it == "manifest 42" }).isEqualTo(1)

        harness.syncEvents.manifestChanged(BookId("42"))
        harness.controller.checkNewerPosition()

        assertThat(harness.audio.calls.count { it == "manifest 42" }).isEqualTo(2)
    }
}
