package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry

/**
 * One read-along file's narration on a single timeline: played segments of the embedded tracks one after the other
 * in reading order, each from its first clip's begin to its last clip's end. Times are in ms on that timeline.
 *
 * Built from the playlist and the spine of the file; the section indexes need not be contiguous (sections without
 * narration have no items). The sentence lookups are [ReadAlongSentences], the narration clock [ReadAlongClock].
 */
class ReadAlongMap private constructor(
    val bookId: BookId,
    val fileId: FileId,
    val contentVersion: String?,
    private val layout: Layout,
) : ReadAlongSentences by layout.sentences, ReadAlongClock by layout.clock {

    constructor(
        bookId: BookId,
        fileId: FileId,
        contentVersion: String?,
        playlist: ReadAlongPlaylist,
        spine: List<SpineEntry>,
    ) : this(bookId, fileId, contentVersion, Layout(playlist, spine))

    /**
     * A played segment of an embedded track: consecutive clips of one file that follow one another.
     *
     * @property clipStartMs where the segment starts inside the file: its first clip's begin.
     * @property clipEndMs where it ends inside the file: its last clip's end.
     * @property startMs where it starts on the map's timeline.
     * @property durationMs `clipEndMs - clipStartMs`.
     */
    data class Track(
        val href: String,
        val mediaType: String,
        val sizeBytes: Long?,
        val clipStartMs: Long,
        val clipEndMs: Long,
        val startMs: Long,
        val durationMs: Long,
    )

    /**
     * Consecutive items with the same fragment are one sentence (a sentence over two tracks).
     *
     * @property ordinal the sentence's place in reading order, which is the playing order.
     * @property fragment the element id in [textHref]; empty when the whole file is meant.
     */
    data class Sentence(
        val ordinal: Int,
        val sectionIndex: Int,
        val fragment: String,
        val textHref: String,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * A section with narration, from its first sentence (in reading order) to the next section's start, or the
     * end of the map; [title] is its label in the table of contents, null when it has none.
     */
    data class NarratedSection(val index: Int, val title: String?, val startMs: Long, val endMs: Long)

    data class SkippedSection(val index: Int, val href: String, val sizeBytes: Long?)

    /** The playlist and spine as the map's parts; made once per map. */
    private class Layout(playlist: ReadAlongPlaylist, spine: List<SpineEntry>) {
        val clips = layOut(playlist.items, playlist.resources)
        val sentences = SentenceIndex(playlist.items, clips)
        val clock = ClipClock(clips)
        val sections = narratedSections(playlist, sentences, clips.totalMs)
        val skipped = sections.map { it.index }.toSet().let { narrated -> spine.filter { it.index !in narrated } }
    }

    /**
     * The played segments in reading order; the same href may come several times (a file whose clips are read in
     * two places). `tracks[i]` is the asset `i` of [toManifest].
     */
    val tracks: List<Track> get() = layout.clips.tracks

    /** In reading order. */
    val sections: List<NarratedSection> get() = layout.sections

    val totalMs: Long get() = layout.clips.totalMs

    /** The spine sections without narration strictly between two narrated sections. */
    fun skippedBetween(fromSection: Int, toSection: Int): List<SkippedSection> = layout.skipped
        .filter { it.index in (fromSection + 1) until toSection }
        .map { SkippedSection(it.index, it.href, it.sizeBytes) }

    /**
     * The server's narration fields at [timeMs] (clamped to the map): the sentence heard, or the first one before
     * any; `fragment` is `textHref#id`. Null for a map without sentences.
     */
    fun placeAt(timeMs: Long): NarrationPlace? {
        val time = timeMs.coerceIn(0, totalMs)
        val sentence = sentenceAt(time) ?: layout.sentences.all.firstOrNull() ?: return null
        val fragment = sentence.fragment.takeIf { it.isNotEmpty() }?.let { "${sentence.textHref}#$it" }
        return NarrationPlace(clockSeconds(time), fragment ?: sentence.textHref, sentence.sectionIndex)
    }
}

private fun narratedSections(
    playlist: ReadAlongPlaylist,
    sentences: SentenceIndex,
    totalMs: Long,
): List<ReadAlongMap.NarratedSection> {
    val labels = playlist.sections.associate { it.index to it.label }
    val starts = sentences.bySection.map { (index, inSection) -> index to inSection.first().startMs }
        .sortedWith(compareBy({ it.second }, { it.first }))
    return starts.mapIndexed { position, (index, start) ->
        val end = starts.getOrNull(position + 1)?.second ?: totalMs
        ReadAlongMap.NarratedSection(index, labels[index], start, end)
    }
}
