package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap.Track
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource

/** A clip starting up to this long before the previous clip's end still follows it (rounded or overlapping times). */
internal const val SegmentBackToleranceMs = 500L

/** A clip starting up to this long after the previous clip's end still follows it (a pause in the same file). */
internal const val SegmentForwardToleranceMs = 30_000L

/**
 * The items of a playlist placed on the map's timeline, as parallel arrays in reading order (the items' order).
 *
 * @property tracks the played segments, one after the other in reading order; none is empty.
 * @property startMs where each clip starts on the timeline: its segment's start plus its offset in the segment,
 *   never before the previous clip's start (clips overlapping by less than [SegmentBackToleranceMs]).
 * @property durationMs each clip's length on the timeline, cut at its segment's end.
 * @property clockStartMs each clip's start on the server's narration clock: the clip lengths before it.
 * @property clockTotalMs the length of every clip.
 */
internal class ReadAlongClips(
    val tracks: List<Track>,
    val startMs: LongArray,
    val durationMs: LongArray,
    val clockStartMs: LongArray,
    val clockTotalMs: Long,
) {
    val size: Int get() = startMs.size

    val totalMs: Long = tracks.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0

    fun endMs(index: Int): Long = startMs[index] + durationMs[index]
}

/** A segment being gathered: consecutive clips of one file that follow one another. */
private class Run(val href: String, val clipStartMs: Long, var clipEndMs: Long) {
    /**
     * True when [item] follows this run: same file, starting within the tolerances of the previous end, which is
     * the run's latest clip end so far.
     */
    fun takes(item: OverlayItem): Boolean = item.audioHref == href &&
        item.clipBeginMs in (clipEndMs - SegmentBackToleranceMs)..(clipEndMs + SegmentForwardToleranceMs)

    val isEmpty: Boolean get() = clipEndMs <= clipStartMs
}

/**
 * Lays [items] out on one timeline of segments in reading order: a new segment starts when the file changes, or
 * when a clip jumps back or forward in its file beyond the tolerances. A segment lasts from its first clip's begin
 * to its latest clip end. A run of clips without length makes no segment (the player cannot place an empty file):
 * its clips take the start of the next segment, or the end of the last one when no segment follows.
 */
internal fun layOut(items: List<OverlayItem>, resources: List<OverlayResource>): ReadAlongClips {
    val ends = clipEnds(items)
    val runs = mutableListOf<Run>()
    val runOf = IntArray(items.size)
    items.forEachIndexed { index, item ->
        val last = runs.lastOrNull()
        if (last != null && last.takes(item)) {
            last.clipEndMs = maxOf(last.clipEndMs, ends[index])
        } else {
            runs += Run(item.audioHref, item.clipBeginMs, ends[index])
        }
        runOf[index] = runs.lastIndex
    }
    val tracks = tracksOf(runs.filterNot { it.isEmpty }, resources)
    val trackOfRun = trackOfRun(runs)
    val lengths = LongArray(items.size) { ends[it] - items[it].clipBeginMs }
    val starts = LongArray(items.size)
    val durations = LongArray(items.size)
    val clockStarts = LongArray(items.size)
    val totalMs = tracks.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0
    items.forEachIndexed { index, item ->
        val track = tracks.getOrNull(trackOfRun[runOf[index]])
        val segmentEnd = track?.let { it.startMs + it.durationMs } ?: totalMs
        // A clip of an empty run sits at the next segment's start, or at the very end.
        val start = when {
            track == null -> totalMs
            runs[runOf[index]].isEmpty -> track.startMs
            else -> track.startMs + (item.clipBeginMs - track.clipStartMs).coerceAtLeast(0)
        }
        val end = if (track != null && !runs[runOf[index]].isEmpty) start + lengths[index] else start
        starts[index] = maxOf(start.coerceAtMost(segmentEnd), if (index == 0) 0 else starts[index - 1])
        durations[index] = (end.coerceAtMost(segmentEnd) - starts[index]).coerceAtLeast(0)
        if (index > 0) clockStarts[index] = clockStarts[index - 1] + lengths[index - 1]
    }
    return ReadAlongClips(tracks, starts, durations, clockStarts, lengths.sum())
}

/**
 * For each run, the index of its segment among the non-empty runs; for an empty run, that of the next segment
 * (past the last one when none follows).
 */
private fun trackOfRun(runs: List<Run>): IntArray {
    val result = IntArray(runs.size)
    var next = runs.count { !it.isEmpty }
    for (index in runs.indices.reversed()) {
        if (!runs[index].isEmpty) next--
        result[index] = next
    }
    return result
}

private fun tracksOf(runs: List<Run>, resources: List<OverlayResource>): List<Track> {
    val byHref = resources.associateBy { it.href }
    var start = 0L
    return runs.map { run ->
        val resource = byHref[run.href]
        val duration = run.clipEndMs - run.clipStartMs
        val (mediaType, size) = resource?.mediaType.orEmpty() to resource?.sizeBytes
        Track(run.href, mediaType, size, run.clipStartMs, run.clipEndMs, start, duration).also { start += duration }
    }
}

/**
 * The end of each clip in its file; never before its begin. A clip without an end runs to the next clip of its
 * file; the last one of its file gets no length (SMIL would mean "to the end of the file", whose length the map does
 * not know; no recorded book has such a clip).
 */
private fun clipEnds(items: List<OverlayItem>): LongArray {
    val ends = LongArray(items.size) { index -> items[index].clipEndMs ?: items[index].clipBeginMs }
    val withoutEnd = items.indices.filter { items[it].clipEndMs == null }.map { items[it].audioHref }.toSet()
    val inFiles = if (withoutEnd.isEmpty()) emptyList() else items.indices.groupBy { items[it].audioHref }.values
    inFiles.filter { items[it.first()].audioHref in withoutEnd }.forEach { inFile ->
        val ordered = inFile.sortedBy { items[it].clipBeginMs }
        ordered.forEachIndexed { position, index ->
            val next = ordered.getOrNull(position + 1)
            if (items[index].clipEndMs == null && next != null) ends[index] = items[next].clipBeginMs
        }
    }
    items.indices.forEach { ends[it] = ends[it].coerceAtLeast(items[it].clipBeginMs) }
    return ends
}

/** The index of the last value at or before [value] in the ascending [values]; -1 when there is none. */
internal fun lastAtOrBefore(values: LongArray, value: Long): Int {
    var low = 0
    var high = values.size - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (values[middle] <= value) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}
