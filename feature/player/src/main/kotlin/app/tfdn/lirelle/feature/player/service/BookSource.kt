package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.ids.FileId
import kotlin.math.abs

/** What a loaded book plays: its audiobook, or the narration of one of its read-along files. */
sealed interface BookSource {
    data object Audiobook : BookSource

    /** The narration of the read-along file [fileId], laid out by [map]. */
    data class Narration(val fileId: FileId, val map: ReadAlongMap) : BookSource
}

/** The read-along file whose narration this is; null for an audiobook. */
val BookSource.narrationFileId: FileId? get() = (this as? BookSource.Narration)?.fileId

/** Both the audiobook, or both the narration of the same file: the same thing to play. */
fun BookSource.sameMedium(other: BookSource): Boolean = narrationFileId == other.narrationFileId

/** Where a narration starts. */
sealed interface NarrationStart {
    /**
     * The sentence [fragment] (its element id; a `textHref#id` is also taken) in the section at [sectionIndex];
     * an empty [fragment] is the section's first sentence.
     */
    data class AtSentence(val sectionIndex: Int, val fragment: String) : NarrationStart

    /** The device's or the server's narration place, newest first; else the first sentence. */
    data object Resume : NarrationStart

    /**
     * The sentence the book's page marker [cfi] is in ([sentenceAtMarker]): a book read further in text than it
     * was heard, started without its reader. As [Resume] when the marker names no sentence. Asked inside the
     * service only: the session's commands do not carry it.
     */
    data class AtMarker(val cfi: String) : NarrationStart
}

/**
 * Where [start] is on the narration: the start of its sentence, or of the first sentence of its section (or of
 * the next narrated one) when the sentence is not known; null when neither is.
 */
fun ReadAlongMap.timeOf(start: NarrationStart.AtSentence): Long? {
    val fragment = start.fragment.substringAfter('#')
    val sentence = fragment.takeIf { it.isNotEmpty() }?.let { sentenceOf(start.sectionIndex, it) }
        ?: firstSentenceFrom(start.sectionIndex)
    return sentence?.startMs
}

/**
 * The end of a section nearest to [timeMs]: the start of the narrated section that follows it, or the end of the
 * narration. A stop at the end of a section lands a little before it, or just after it.
 */
fun ReadAlongMap.sectionEndNear(timeMs: Long): Long =
    (sections.map { it.startMs } + totalMs).minBy { abs(it - timeMs) }

/** Where a narration takes up again from [timeMs]: the start of the sentence heard there. */
fun ReadAlongMap.sentenceStartAt(timeMs: Long): Long = sentenceAt(timeMs)?.startMs ?: timeMs
