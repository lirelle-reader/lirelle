package app.tfdn.lirelle.core.audio.timeline

import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import kotlin.math.roundToLong

/**
 * The whole book as one timeline over its files, from the manifest.
 *
 * Book time runs from 0 to [totalMs] across the files in their sequence order; chapter
 * bounds are in book time. Every time given in is clamped to the book.
 *
 * A file whose duration the server does not know lasts the whole book when it is the only one
 * ([AudioManifest.totalDurationMs]). Among several, it takes every book time from its start on:
 * nothing says where it ends, nor where the files after it start, which have no book time. A time
 * is never clamped to a duration nobody knows; [totalMs] then only counts what is known, and
 * [durationsKnown] is false.
 */
class BookTimeline(manifest: AudioManifest) {

    private val assets: List<AudioAsset> = manifest.assets.sortedBy { it.sequence }

    /** The duration of each file, by [AudioAsset.assetId]; null when unknown. */
    private val durations: Map<String, Long?> = assets.associate { asset ->
        val single = manifest.totalDurationMs.takeIf { assets.size == 1 && it > 0 }
        asset.assetId to (asset.durationMs?.takeIf { it > 0 } ?: single)
    }

    /** Where each file starts on the book's timeline, by [AudioAsset.assetId]. */
    private val assetStarts: Map<String, Long> = buildMap {
        var start = 0L
        assets.forEach { asset ->
            put(asset.assetId, start)
            start += durations[asset.assetId] ?: 0
        }
    }

    /** The files that have a place on the timeline: up to the first of unknown duration, included. */
    private val placed: Set<String> = buildSet {
        for (asset in assets) {
            add(asset.assetId)
            if (durations[asset.assetId] == null) break
        }
    }

    /** False when a file's duration is unknown: [totalMs], percentages and time left are then not the book's. */
    val durationsKnown: Boolean = durations.values.all { it != null }

    val totalMs: Long = durations.values.sumOf { it ?: 0 }

    val chapters: List<AudioChapter> = manifest.chapters.sortedBy { it.startMs }

    /**
     * The book time of [positionMs] inside [assetId], clamped to that file; null for a file the
     * book does not have, or one after a file of unknown duration.
     */
    fun bookTimeOf(assetId: String, positionMs: Long): Long? {
        if (assetId !in placed) return null
        val start = assetStarts.getValue(assetId)
        val duration = durations[assetId]
        return start + if (duration == null) positionMs.coerceAtLeast(0) else positionMs.coerceIn(0, duration)
    }

    /**
     * The file playing at [bookTimeMs] and the position inside it; the end of the book is the end
     * of the last file. Null for a book without files.
     */
    fun assetPositionOf(bookTimeMs: Long): Pair<AudioAsset, Long>? {
        if (assets.isEmpty()) return null
        val time = clamp(bookTimeMs)
        val asset = assets.firstOrNull { asset ->
            val duration = durations[asset.assetId]
            duration == null || time < assetStarts.getValue(asset.assetId) + duration
        } ?: assets.last()
        return asset to time - assetStarts.getValue(asset.assetId)
    }

    /**
     * The index into [chapters] of the chapter at [bookTimeMs], or -1 when the book has no chapters.
     * Before the first chapter, it is the first one.
     */
    fun chapterAt(bookTimeMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val time = clamp(bookTimeMs)
        return chapters.indexOfLast { it.startMs <= time }.coerceAtLeast(0)
    }

    /**
     * In the first [graceMs] of a chapter, the start of the previous one; later, the start of the
     * current one. Before the first chapter, the start of the book.
     */
    fun previousChapterTarget(bookTimeMs: Long, graceMs: Long = DEFAULT_GRACE_MS): Long {
        val index = chapterAt(bookTimeMs)
        if (index < 0 || clamp(bookTimeMs) < chapters[0].startMs) return 0
        val start = chapters[index].startMs
        val inGrace = clamp(bookTimeMs) - start < graceMs
        return if (inGrace && index > 0) chapters[index - 1].startMs else start
    }

    /** The start of the next chapter; the first one before it; null on the last one. */
    fun nextChapterTarget(bookTimeMs: Long): Long? {
        val index = chapterAt(bookTimeMs)
        if (index < 0) return null
        if (clamp(bookTimeMs) < chapters[0].startMs) return chapters[0].startMs
        return chapters.getOrNull(index + 1)?.startMs
    }

    /** How far into the book [bookTimeMs] is, from 0 to 100. */
    fun percentAt(bookTimeMs: Long): Double =
        if (totalMs <= 0) 0.0 else clamp(bookTimeMs).coerceAtMost(totalMs) * PERCENT / totalMs

    /** The listening time left from [bookTimeMs] at [speed]. */
    fun timeLeftMs(bookTimeMs: Long, speed: Float): Long {
        require(speed > 0f) { "speed must be positive: $speed" }
        return ((totalMs - clamp(bookTimeMs)).coerceAtLeast(0) / speed.toDouble()).roundToLong()
    }

    /** Into the book; past [totalMs] only when a duration is unknown, as the book may go on. */
    private fun clamp(bookTimeMs: Long): Long =
        if (durationsKnown) bookTimeMs.coerceIn(0, totalMs) else bookTimeMs.coerceAtLeast(0)

    private companion object {
        const val DEFAULT_GRACE_MS = 3_000L
        const val PERCENT = 100.0
    }
}
