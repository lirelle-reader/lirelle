package app.tfdn.lirelle.feature.player.controller

import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SessionSnapshotTest {

    /** A player in a fixed [state], as a controller sees the session's. */
    private class StubPlayer(private val fixed: State) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = fixed
    }

    private fun chapter(
        index: Int,
        title: String,
        startMs: Long,
        endMs: Long?,
        more: Bundle.() -> Unit = {},
    ): SimpleBasePlayer.MediaItemData {
        val extras = Bundle().apply {
            putLong(PlayerSessionContract.EXTRA_CHAPTER_START_MS, startMs)
            endMs?.let { putLong(PlayerSessionContract.EXTRA_CHAPTER_END_MS, it) }
            more()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(if (index == 1) "Stops in 3 min" else "The Long Road")
            .setSubtitle(title)
            .setExtras(extras)
            .build()
        val item = MediaItem.Builder().setMediaId("42/$index").setMediaMetadata(metadata).build()
        return SimpleBasePlayer.MediaItemData.Builder("42/chapter-$index").setMediaItem(item).build()
    }

    private val book = listOf(
        chapter(0, "Opening", 0, 60_000),
        chapter(1, "Middle", 60_000, 100_000),
        chapter(2, "Closing", 100_000, null),
    )

    private fun player(
        playbackState: Int = Player.STATE_READY,
        chapters: List<SimpleBasePlayer.MediaItemData> = book,
    ) = StubPlayer(
        SimpleBasePlayer.State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(chapters)
            .setCurrentMediaItemIndex(1)
            .setContentPositionMs(12_000)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackParameters(PlaybackParameters(1.5f))
            .build(),
    )

    @Test
    fun `an error that stopped the player shows`() {
        val failed = StubPlayer(
            SimpleBasePlayer.State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(book)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                .build(),
        )

        assertThat(failed.snapshot(PlayerSessionExtras()).error).isTrue()
        assertThat(player().snapshot(PlayerSessionExtras()).error).isFalse()
    }

    @Test
    fun `the chapters come from the items, the place from the player`() {
        val extras = PlayerSessionExtras(SessionBooks.book)

        val snapshot = player().snapshot(extras)

        assertThat(snapshot.extras).isEqualTo(extras)
        assertThat(snapshot.chapters).containsExactly(
            SessionChapter("Opening", 0, 60_000),
            SessionChapter("Middle", 60_000, 100_000),
            SessionChapter("Closing", 100_000, null),
        ).inOrder()
        assertThat(snapshot.chapterIndex).isEqualTo(1)
        assertThat(snapshot.positionMs).isEqualTo(12_000)
        assertThat(snapshot.bookTimeMs).isEqualTo(72_000)
        assertThat(snapshot.playWhenReady).isTrue()
        assertThat(snapshot.buffering).isFalse()
        assertThat(snapshot.speed).isEqualTo(1.5f)
    }

    @Test
    fun `waiting for data is buffering`() {
        assertThat(player(Player.STATE_BUFFERING).snapshot(PlayerSessionExtras()).buffering).isTrue()
    }

    @Test
    fun `at the end of the book playback has ended, and does not play`() {
        val player = player(Player.STATE_ENDED)

        assertThat(player.snapshot(PlayerSessionExtras()).ended).isTrue()
        assertThat(player.playsOrWill).isFalse()
        assertThat(player().playsOrWill).isTrue()
    }

    @Test
    fun `the chapters of a timeline that did not change are the same list, not made again`() {
        val player = player()
        val cache = ChapterCache()

        val first = player.snapshot(PlayerSessionExtras(), cache).chapters
        val second = player.snapshot(PlayerSessionExtras(), cache).chapters

        assertThat(second).isSameInstanceAs(first)
        assertThat(first.map { it.title }).containsExactly("Opening", "Middle", "Closing").inOrder()
        // Another timeline is read again.
        assertThat(player().snapshot(PlayerSessionExtras(), cache).chapters).isNotSameInstanceAs(first)
    }

    @Test
    fun `a narration's chapters carry their section's place in the book`() {
        val sections = listOf(
            chapter(0, "Opening", 0, 15_000) { putInt(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX, 1) },
            chapter(1, "", 15_000, 25_000) { putInt(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX, 3) },
        )

        val snapshot = player(chapters = sections).snapshot(PlayerSessionExtras())

        assertThat(snapshot.chapters).containsExactly(
            SessionChapter("Opening", 0, 15_000, sectionIndex = 1),
            SessionChapter("", 15_000, 25_000, sectionIndex = 3),
        ).inOrder()
    }

    @Test
    fun `an audiobook's chapters carry their manifest id`() {
        val chapters = listOf(
            chapter(0, "Opening", 0, 60_000) { putString(PlayerSessionContract.EXTRA_CHAPTER_ID, "c1") },
            chapter(1, "Middle", 60_000, null),
        )

        val snapshot = player(chapters = chapters).snapshot(PlayerSessionExtras())

        assertThat(snapshot.chapters.map { it.chapterId }).containsExactly("c1", null).inOrder()
    }
}
