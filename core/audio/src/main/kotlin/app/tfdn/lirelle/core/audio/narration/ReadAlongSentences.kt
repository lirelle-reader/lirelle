package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap.Sentence
import app.tfdn.lirelle.core.library.model.OverlayItem

/** ⏮ within this long after a sentence's start goes to the previous sentence. */
const val PreviousSentenceGraceMs: Long = 2_000

/** The sentences of a narration, in reading order, which is also the order they play in. */
interface ReadAlongSentences {
    /**
     * The sentence heard at [timeMs]: the last one started at or before it, so that between two clips the
     * previous one stays. Null before the first sentence and beyond the map; at the very end, the last one.
     */
    fun sentenceAt(timeMs: Long): Sentence?

    /** The start of the current sentence, or the previous one when [timeMs] is within [graceMs] of that start. */
    fun previousSentence(timeMs: Long, graceMs: Long = PreviousSentenceGraceMs): Sentence?

    /** The first sentence starting after [timeMs]; null on the last one. */
    fun nextSentence(timeMs: Long): Sentence?

    /** The first sentence with that [fragment] in the section at [sectionIndex]. */
    fun sentenceOf(sectionIndex: Int, fragment: String): Sentence?

    /** The first sentence of [sectionIndex], or of the next narrated section after it. */
    fun firstSentenceFrom(sectionIndex: Int): Sentence?

    /** The section's fragments in reading order, without duplicates; empty for a section without narration. */
    fun fragmentsOf(sectionIndex: Int): List<String>

    /** True for the last sentence of the narration. */
    fun isLastSentence(sentence: Sentence): Boolean
}

internal class SentenceIndex(items: List<OverlayItem>, clips: ReadAlongClips) : ReadAlongSentences {

    /** In reading order: [Sentence.ordinal] is the place in this list. */
    val all: List<Sentence> = merge(items, clips)

    private val starts = all.map { it.startMs }.toLongArray()
    private val totalMs = clips.totalMs

    /** Each section's sentences, in reading order, by section index. */
    val bySection: Map<Int, List<Sentence>> = all.groupBy { it.sectionIndex }

    private val narrated = bySection.keys.sorted()

    private val byFragment: Map<Pair<Int, String>, Sentence> = buildMap {
        all.forEach { putIfAbsent(it.sectionIndex to it.fragment, it) }
    }

    override fun sentenceAt(timeMs: Long): Sentence? {
        if (timeMs < 0 || timeMs > totalMs) return null
        return all.getOrNull(lastAtOrBefore(starts, timeMs))
    }

    override fun previousSentence(timeMs: Long, graceMs: Long): Sentence? {
        val current = sentenceAt(timeMs.coerceIn(0, totalMs)) ?: all.firstOrNull() ?: return null
        val inGrace = timeMs - current.startMs < graceMs
        return if (inGrace && current.ordinal > 0) all[current.ordinal - 1] else current
    }

    override fun nextSentence(timeMs: Long): Sentence? = all.getOrNull(lastAtOrBefore(starts, timeMs) + 1)

    override fun sentenceOf(sectionIndex: Int, fragment: String): Sentence? = byFragment[sectionIndex to fragment]

    override fun firstSentenceFrom(sectionIndex: Int): Sentence? =
        narrated.firstOrNull { it >= sectionIndex }?.let { bySection.getValue(it).first() }

    override fun fragmentsOf(sectionIndex: Int): List<String> =
        bySection[sectionIndex].orEmpty().map { it.fragment }.filter { it.isNotEmpty() }.distinct()

    override fun isLastSentence(sentence: Sentence): Boolean = sentence.ordinal == all.lastIndex
}

/** Consecutive items of the same text and fragment are one sentence, from the first one's start to the last's end. */
private fun merge(items: List<OverlayItem>, clips: ReadAlongClips): List<Sentence> {
    val sentences = mutableListOf<Sentence>()
    items.forEachIndexed { index, item ->
        val previous = items.getOrNull(index - 1)
        val same = previous != null && previous.textHref == item.textHref && previous.fragment == item.fragment &&
            previous.sectionIndex == item.sectionIndex
        if (same) {
            sentences[sentences.lastIndex] = sentences.last().copy(endMs = clips.endMs(index))
        } else {
            val (start, end) = clips.startMs[index] to clips.endMs(index)
            val fragment = item.fragment.orEmpty()
            sentences += Sentence(sentences.size, item.sectionIndex, fragment, item.textHref, start, end)
        }
    }
    return sentences
}
