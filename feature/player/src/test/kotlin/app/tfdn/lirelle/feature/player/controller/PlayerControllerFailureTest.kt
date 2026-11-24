package app.tfdn.lirelle.feature.player.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Under Robolectric: a failure is logged. */
@RunWith(AndroidJUnit4::class)
class PlayerControllerFailureTest {

    private val later = Instant.parse("2026-11-15T10:00:00Z")

    @Test
    fun `a failure while looking at a conflict does not stop the next ones from being offered`() = runTest {
        val harness = PlayerHarness(this)
        var failures = 1
        harness.audioRepository = object : AudioRepository by harness.audio {
            override suspend fun manifest(bookId: BookId): ApiResult<AudioManifest> {
                if (failures-- > 0) error("broken")
                return harness.audio.manifest(bookId)
            }
        }
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(later))
        harness.show(SessionBooks.at())
        val state = harness.watched()
        val tick = LocalPlayback(BookId("42"), "a1", 70_000, 70_000, later.plusSeconds(60), contentVersion = "rev-1")
        harness.store.put(tick)

        harness.conflicts.dropped(tick, SessionBooks.server(later))
        assertThat(state.value.newerPosition).isNull()

        harness.conflicts.dropped(tick, SessionBooks.server(later.plusSeconds(5), version = "v8"))

        assertThat(state.value.newerPosition)
            .isEqualTo(OfferedListening(120_000, later.plusSeconds(5), OfferedChapter(3, "Closing", 20_000)))
    }
}
