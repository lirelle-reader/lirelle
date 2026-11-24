package app.tfdn.lirelle.feature.player.controller

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import kotlinx.coroutines.flow.StateFlow

/**
 * A chapter of the session's playlist, its bounds on the whole book's timeline; [endMs] null when unknown.
 * [sectionIndex]: a narration's chapter, its section's place in the book's reading order; null for an audiobook's.
 * [chapterId]: the manifest chapter's id; null for the one chapter of a book without any.
 */
data class SessionChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long?,
    val sectionIndex: Int? = null,
    val chapterId: String? = null,
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }
}

/**
 * What the playback session shows at a moment: its extras, its chapters, where playback is in the
 * current one, and how it plays. [ended]: playback reached the end of the book (the player keeps
 * [playWhenReady] then). [error]: an error stopped the player (a network one included).
 */
data class SessionSnapshot(
    val extras: PlayerSessionExtras = PlayerSessionExtras(),
    val chapters: List<SessionChapter> = emptyList(),
    val chapterIndex: Int = 0,
    val positionMs: Long = 0,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val speed: Float = 1f,
    val error: Boolean = false,
) {
    val bookId: BookId? get() = extras.book?.id

    /** The book shown is a narration (a read-along book's audio), not an audiobook. */
    val narrating: Boolean get() = extras.narrationFileId != null

    val chapter: SessionChapter? get() = chapters.getOrNull(chapterIndex)

    /** Where playback is in the current chapter, never past its known end. */
    val chapterElapsedMs: Long
        get() {
            val elapsed = positionMs.coerceAtLeast(0)
            return chapter?.durationMs?.let { elapsed.coerceAtMost(it) } ?: elapsed
        }

    /** Where playback is on the whole book's timeline. */
    val bookTimeMs: Long get() = (chapter?.startMs ?: 0) + chapterElapsedMs
}

/** The playback session, as the app's screens see and command it (a MediaController in the app). */
interface PlayerConnection {
    /** Watching it keeps the connection to the session open. */
    val snapshot: StateFlow<SessionSnapshot>

    fun send(command: PlayerCommand)

    /** Pauses when the session plays (or is about to), plays otherwise: decided from the session itself. */
    fun playPause()

    /** Plays the session, or pauses it: never a toggle, nothing changes when it already does. */
    fun setPlaying(playing: Boolean)

    /** Pauses the session; nothing changes when it does not play. */
    fun pause() = setPlaying(false)

    /** Plays the session; nothing changes when it plays already. */
    fun play() = setPlaying(true)

    fun seekTo(chapterIndex: Int, positionMs: Long)

    /** True while the session is reached and [snapshot] is read from it; the first snapshot is always empty. */
    val connected: StateFlow<Boolean>

    /** Forgets the session shown, as when it goes away. */
    fun clear()
}

/** Whether play was asked and playback has not reached the end: the button shows pause. */
val Player.playsOrWill: Boolean get() = playWhenReady && playbackState != Player.STATE_ENDED

/**
 * The chapters of a player's timeline, read again only when the timeline is another: a book's hundreds of
 * chapters are not made again at each look at the place.
 */
class ChapterCache {
    private var timeline: Timeline? = null
    private var chapters: List<SessionChapter> = emptyList()

    fun of(player: Player): List<SessionChapter> {
        val now = player.currentTimeline
        if (now !== timeline) {
            timeline = now
            chapters = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.chapter() }
        }
        return chapters
    }
}

/**
 * What a player of the session (a MediaController) shows, read from its timeline and its items'
 * metadata, so that it holds however the player got there: a book loaded through playback
 * resumption announces no item transition. The chapters come from [chapters], kept as long as the
 * timeline is the same.
 */
fun Player.snapshot(extras: PlayerSessionExtras, chapters: ChapterCache = ChapterCache()): SessionSnapshot =
    SessionSnapshot(
        extras = extras,
        chapters = chapters.of(this),
        chapterIndex = currentMediaItemIndex,
        positionMs = currentPosition,
        playWhenReady = playWhenReady,
        buffering = playbackState == Player.STATE_BUFFERING,
        ended = playbackState == Player.STATE_ENDED,
        speed = playbackParameters.speed,
        error = playerError != null,
    )

private fun MediaMetadata.chapter(): SessionChapter {
    val end = extras?.takeIf { it.containsKey(PlayerSessionContract.EXTRA_CHAPTER_END_MS) }
        ?.getLong(PlayerSessionContract.EXTRA_CHAPTER_END_MS)
    val section = extras?.takeIf { it.containsKey(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX) }
        ?.getInt(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX)
    val id = extras?.getString(PlayerSessionContract.EXTRA_CHAPTER_ID)
    return SessionChapter(subtitle?.toString().orEmpty(), PlayerSessionContract.chapterStartMs(this), end, section, id)
}
