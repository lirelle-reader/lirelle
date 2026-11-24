@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

private const val UsPerMs = 1_000L
private const val DefaultBackMs = 15_000L
private const val DefaultForwardMs = 30_000L

/** What a book offers to controllers: playing, seeking by chapter and by skips, the speed. Never its items. */
private val BookCommands = Player.Commands.Builder().addAll(
    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SPEED_AND_PITCH,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_MEDIA_ITEM,
).build()

/** At the end of the book: no play, skipping or chapter stepping (nor their buttons), only choosing a chapter. */
private val EndedCommands = BookCommands.buildUpon().removeAll(
    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_PLAY_PAUSE,
).build()

/** At the end of a narration: play too, which starts it over (as "Listen again" does an audiobook). */
private val NarrationEndedCommands = EndedCommands.buildUpon().add(Player.COMMAND_PLAY_PAUSE).build()

/**
 * Without a book, and while the last book loads: reading the (empty) state, setting the last book (playback
 * resumption), and play/pause. Play is what asks for the last book: Media3 drops the play button of its own
 * notification, left behind by a process the system ended, when the player does not offer it. With no last
 * book it does nothing; while the last book loads, a pause is kept for when it is loaded.
 */
private val NoBookCommands = Player.Commands.Builder().addAll(
    Player.COMMAND_RELEASE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_PLAY_PAUSE,
).build()

/**
 * The player the media session shows: the engine (one item per file) seen as one item per chapter, so that
 * the notification, the lock screen and every controller show and seek the current chapter.
 *
 * This is how the notification's progress follows the chapter: a [ForwardingSimpleBasePlayer] whose playlist
 * is the chapters, whose position and duration are the chapter's, and whose seeks are turned into places in
 * the book ([BookNavigator]). Seeking within the chapter therefore stays within it on every surface.
 *
 * "Next" and "previous" (`seekToNext`, `seekToPrevious`, from headsets, steering wheels and watches) and
 * `seekForward` / `seekBack` skip forward and back ([Actions]); `seekToNextMediaItem` and
 * `seekToPreviousMediaItem` go to the next and previous chapters. Play goes through [Actions.play] (the
 * rewind on resume); the speed through [Actions.setSpeed] (kept for the book).
 *
 * Setting the last book's [PlayerSessionContract.resumeItem] (playback resumption) loads that book.
 *
 * Call [refresh] when playback may have crossed into another chapter (every tick).
 */
@OptIn(UnstableApi::class)
class ChapterPlayer(
    private val engine: Player,
    private val navigator: BookNavigator,
    private val actions: Actions,
) : ForwardingSimpleBasePlayer(engine) {

    interface Actions {
        fun play()

        fun forward()

        fun back()

        fun setSpeed(speed: Float)

        /** Loads [bookId], playing it if [play] still says so then; [done] once it is loaded or could not be. */
        fun resume(bookId: BookId, play: () -> Boolean, done: () -> Unit)
    }

    /** The skip intervals of the settings, as the player's seek increments. */
    data class Skips(val backMs: Long = DefaultBackMs, val forwardMs: Long = DefaultForwardMs)

    var skips: Skips = Skips()
        set(value) {
            if (field == value) return
            field = value
            invalidateState()
        }

    /** Another title or text for the current chapter: the sleep timer's time left, "finished". */
    data class Override(val title: CharSequence? = null, val artist: CharSequence? = null)

    var override: Override = Override()
        set(value) {
            if (field == value) return
            field = value
            invalidateState()
        }

    /** The last book being loaded for a play button (playback resumption). */
    private var resuming: MediaItem? = null

    /** Pause was pressed while the last book loaded: it stays paused once loaded. */
    private var pausedWhileResuming = false

    private var itemsOf: BookPlaylist? = null
    private var chapterItems: List<MediaItemData> = emptyList()

    /** The engine may have moved into another chapter: tell the session. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val state = super.getState()
        val book = navigator.playlist
        if (book == null || engine.currentTimeline.isEmpty) {
            return state.buildUpon().setAvailableCommands(NoBookCommands).build()
        }
        val index = navigator.chapterIndex().coerceIn(0, book.chapters.lastIndex)
        val chapter = book.chapters[index]
        val inChapter = { bookMs: Long? -> chapter.clamp((bookMs ?: chapter.startMs) - chapter.startMs) }
        val ended = engine.playbackState == Player.STATE_ENDED
        return state.buildUpon()
            .setPlaylist(itemsFor(book, index))
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { inChapter(navigator.bookTimeMs()) }
            .setContentBufferedPositionMs {
                inChapter(book.bookTimeOf(engine.currentMediaItemIndex, engine.bufferedPosition))
            }
            .setAvailableCommands(
                when {
                    !ended -> BookCommands
                    book.narrated -> NarrationEndedCommands
                    else -> EndedCommands
                },
            )
            .setSeekBackIncrementMs(skips.backMs)
            .setSeekForwardIncrementMs(skips.forwardMs)
            .apply {
                if (state.hasPositionDiscontinuity) {
                    setPositionDiscontinuity(state.positionDiscontinuityReason, inChapter(navigator.bookTimeMs()))
                }
            }
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Play at the end of the book first asks for the item's default position: the end stays.
        val ended = engine.playbackState == Player.STATE_ENDED
        if (ended && seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION) return Futures.immediateVoidFuture()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_FORWARD -> actions.forward()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_BACK -> actions.back()
            else -> if (mediaItemIndex != C.INDEX_UNSET) {
                navigator.seekToChapter(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0 else positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (resuming != null) {
            pausedWhileResuming = !playWhenReady
            return Futures.immediateVoidFuture()
        }
        if (!playWhenReady) return super.handleSetPlayWhenReady(false)
        // At its end, an audiobook does not play again (its end panel offers to); a narration starts over.
        if (engine.playbackState != Player.STATE_ENDED || navigator.playlist?.narrated == true) actions.play()
        return Futures.immediateVoidFuture()
    }

    /** Only the last book's [PlayerSessionContract.resumeItem]: a play button after the service ended. */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        val bookId = item?.let { PlayerSessionContract.resumedBookId(it.mediaId) }
            ?: return Futures.immediateVoidFuture()
        // The session shows the placeholder until the book is loaded.
        val loaded = SettableFuture.create<Unit>()
        resuming = item
        pausedWhileResuming = false
        actions.resume(bookId, { !pausedWhileResuming }) {
            resuming = null
            pausedWhileResuming = false
            loaded.set(Unit)
        }
        return loaded
    }

    /**
     * While the last book loads, the session shows it as starting to play: Media3 posts the notification and
     * enters the foreground at once, as the system requires of a service started by a play button. If the
     * book cannot be loaded, the empty state that follows lets Media3 leave the foreground.
     */
    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        val item = resuming ?: return suggestedPlaceholderState
        val placeholder = MediaItemData.Builder("resume/${item.mediaId}")
            .setMediaItem(item)
            .setMediaMetadata(item.mediaMetadata)
            .setIsPlaceholder(true)
            .build()
        return suggestedPlaceholderState.buildUpon()
            .setPlaylist(listOf(placeholder))
            .setCurrentMediaItemIndex(0)
            .setPlayerError(null)
            .setAvailableCommands(NoBookCommands)
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(!pausedWhileResuming, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        actions.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    private fun itemsFor(book: BookPlaylist, current: Int): List<MediaItemData> {
        if (itemsOf !== book) {
            itemsOf = book
            chapterItems = book.chapters.mapIndexed { index, chapter -> itemData(book, index, chapter, Override()) }
        }
        if (override == Override()) return chapterItems
        return chapterItems.toMutableList().also {
            it[current] = itemData(book, current, book.chapters[current], override)
        }
    }

    private fun itemData(book: BookPlaylist, index: Int, chapter: PlaylistChapter, override: Override): MediaItemData {
        val extras = Bundle().apply {
            putLong(PlayerSessionContract.EXTRA_CHAPTER_START_MS, chapter.startMs)
            chapter.endMs?.let { putLong(PlayerSessionContract.EXTRA_CHAPTER_END_MS, it) }
            chapter.sectionIndex?.let { putInt(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX, it) }
            chapter.chapterId?.let { putString(PlayerSessionContract.EXTRA_CHAPTER_ID, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(override.title ?: book.manifest.title)
            .setSubtitle(chapter.title)
            .setArtist(override.artist ?: chapter.title)
            .setAlbumTitle(book.manifest.title)
            .setArtworkUri(book.artworkUrl?.let(Uri::parse))
            .setExtras(extras)
            .build()
        val item = MediaItem.Builder().setMediaId("${book.manifest.bookId}/$index").setMediaMetadata(metadata).build()
        // Unique across books: another book is another item, even at the same chapter.
        return MediaItemData.Builder("${book.manifest.bookId}/chapter-$index")
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setDurationUs(chapter.durationMs?.let { it * UsPerMs } ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
    }
}
