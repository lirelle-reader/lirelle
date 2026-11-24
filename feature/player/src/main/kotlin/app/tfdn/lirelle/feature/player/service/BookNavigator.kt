package app.tfdn.lirelle.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap

/**
 * Moves the engine (the player of the files) on the whole book's timeline: skips, chapters, places.
 * Call it on the engine's thread.
 */
@OptIn(UnstableApi::class)
class BookNavigator(private val engine: Player) {
    var playlist: BookPlaylist? = null
        private set

    /** Gives the engine [book]'s files, placed at [startMs] of the book. */
    fun open(book: BookPlaylist, startMs: Long) {
        playlist = book
        val start = book.itemPositionOf(startMs) ?: ItemPosition(0, 0)
        engine.setMediaItems(book.items, start.index, start.positionMs)
    }

    fun clear() {
        playlist = null
        engine.clearMediaItems()
    }

    /** Where the engine is in the book; null without a book. */
    fun bookTimeMs(): Long? = playlist?.bookTimeOf(engine.currentMediaItemIndex, engine.currentPosition)

    fun seekBookTime(bookTimeMs: Long) {
        val place = playlist?.itemPositionOf(bookTimeMs) ?: return
        engine.seekTo(place.index, place.positionMs)
    }

    /** Forward ([deltaMs] > 0) or back, across files, never out of the book. */
    fun skip(deltaMs: Long) {
        val book = playlist ?: return
        val now = bookTimeMs() ?: return
        val target = (now + deltaMs).coerceAtLeast(0)
        seekBookTime(if (book.timeline.durationsKnown) target.coerceAtMost(book.timeline.totalMs) else target)
    }

    /** The start of the chapter, or of the previous one in the first seconds of a chapter. */
    fun previousChapter() {
        val book = playlist ?: return
        seekBookTime(book.timeline.previousChapterTarget(bookTimeMs() ?: return))
    }

    /** The start of the next chapter; nothing on the last one. */
    fun nextChapter() {
        val book = playlist ?: return
        book.timeline.nextChapterTarget(bookTimeMs() ?: return)?.let(::seekBookTime)
    }

    /** [positionMs] into the chapter at [index] of [BookPlaylist.chapters]. */
    fun seekToChapter(index: Int, positionMs: Long) {
        val chapter = playlist?.chapters?.getOrNull(index) ?: return
        seekBookTime(chapter.startMs + chapter.clamp(positionMs))
    }

    /** The index of the chapter playing; 0 without a book. */
    fun chapterIndex(): Int = bookTimeMs()?.let { playlist?.chapterIndexAt(it) } ?: 0

    /** How far into its chapter playback is, within the chapter's bounds. */
    fun positionInChapter(): Long {
        val chapter = playlist?.chapters?.getOrNull(chapterIndex()) ?: return 0
        return chapter.clamp((bookTimeMs() ?: 0) - chapter.startMs)
    }
}

/** The start of the sentence heard, or of the one before in its first seconds. */
fun BookNavigator.previousSentence(map: ReadAlongMap) {
    val now = bookTimeMs() ?: return
    map.previousSentence(now)?.let { seekBookTime(it.startMs) }
}

/** The start of the next sentence; nothing on the last one. */
fun BookNavigator.nextSentence(map: ReadAlongMap) {
    val now = bookTimeMs() ?: return
    map.nextSentence(now)?.let { seekBookTime(it.startMs) }
}
