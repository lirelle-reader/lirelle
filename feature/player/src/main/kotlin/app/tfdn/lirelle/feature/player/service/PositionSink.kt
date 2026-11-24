package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import java.time.Clock

/** Where the session loop sends positions: playback states (3a) or narration places (3b). */
interface PositionSink {
    /** Now, on the clock the five seconds between two sends are measured by: for the loop's tick to stamp. */
    fun now(): Long

    /** While playing, at [itemPositionMs] into the item at [itemIndex], [bookTimeMs] into the book. */
    suspend fun onTick(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long, tickedAt: Long)

    /** A pause, a seek, a chapter, the end, leaving: stored and sent now. */
    suspend fun flushNow(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long)
}

/** Where the positions of [playlist] go: the loop sends none of another playlist there. */
data class BookPositions(val playlist: BookPlaylist, val sink: PositionSink)

/** The audiobook's positions: playback states, through the 3a [PositionWriter]. */
class PlaybackSink(
    private val writer: PositionWriter,
    private val playlist: BookPlaylist,
    private val wall: Clock,
) : PositionSink {
    override fun now(): Long = writer.now()

    override suspend fun onTick(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long, tickedAt: Long) {
        position(bookTimeMs, itemIndex, itemPositionMs)?.let { writer.onTick(it, tickedAt) }
    }

    override suspend fun flushNow(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long) {
        position(bookTimeMs, itemIndex, itemPositionMs)?.let { writer.flushNow(it) }
    }

    private fun position(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long): LocalPlayback? {
        val asset = playlist.assets.getOrNull(itemIndex) ?: return null
        return LocalPlayback(
            bookId = playlist.manifest.bookId,
            assetId = asset.assetId,
            positionMs = itemPositionMs.coerceAtLeast(0),
            bookTimeMs = bookTimeMs,
            at = wall.instant(),
            contentVersion = playlist.manifest.contentVersion,
        )
    }
}

/**
 * A narration's positions: the sentence heard and the percentage on the narration clock, through the
 * [NarrationPositionWriter]. Nothing goes out for a map without sentences.
 */
class NarrationSink(
    private val writer: NarrationPositionWriter,
    private val fileId: FileId,
    private val map: ReadAlongMap,
    private val wall: Clock,
) : PositionSink {
    override fun now(): Long = writer.now()

    override suspend fun onTick(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long, tickedAt: Long) {
        narration(bookTimeMs)?.let { writer.onTick(it, tickedAt) }
    }

    override suspend fun flushNow(bookTimeMs: Long, itemIndex: Int, itemPositionMs: Long) {
        narration(bookTimeMs)?.let { writer.flushNow(it) }
    }

    private fun narration(bookTimeMs: Long): LocalNarration? = map.placeAt(bookTimeMs)?.let {
        LocalNarration(fileId, it, map.percentAt(bookTimeMs), wall.instant())
    }
}
