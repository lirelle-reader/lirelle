package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId

/**
 * One sentence of a read-along book: the text it is, and the stretch of audio that reads it.
 *
 * @property fragment the element id in [textHref]; null when the whole file is meant.
 * @property clipEndMs null when the sentence runs to the end of the track.
 */
data class OverlayItem(
    val sectionIndex: Int,
    val textHref: String,
    val fragment: String?,
    val audioHref: String,
    val clipBeginMs: Long,
    val clipEndMs: Long?,
)

/** A chapter that has narration; [index] is its place in the spine. */
data class OverlaySection(val index: Int, val href: String, val label: String?)

data class OverlayResource(val href: String, val mediaType: String, val sizeBytes: Long?)

data class ReadAlongPlaylist(
    val bookId: BookId,
    val fileId: FileId,
    val durationMs: Long?,
    val items: List<OverlayItem>,
    val sections: List<OverlaySection>,
    val resources: List<OverlayResource>,
)

/** One file of the reading order, [index] being its place in it. */
data class SpineEntry(val index: Int, val href: String, val sizeBytes: Long?)
