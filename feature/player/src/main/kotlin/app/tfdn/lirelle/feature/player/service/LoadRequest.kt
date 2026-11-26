package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId

/** What a load asks for: a book's audiobook, or the narration of one of its read-along files. */
sealed interface LoadRequest {
    val bookId: BookId

    /** [fromStart] or [at] a book time (the same book after its files changed); else where it was left. */
    data class Audiobook(override val bookId: BookId, val fromStart: Boolean = false, val at: Long? = null) :
        LoadRequest

    data class Narration(override val bookId: BookId, val fileId: FileId, val start: NarrationStart) : LoadRequest
}

/** Asked from its start: an audiobook listened to again. */
val LoadRequest.fromStart: Boolean get() = this is LoadRequest.Audiobook && fromStart

/** Whether what is held may play on where it is: not when asked from the start, or at a place. */
val LoadRequest.mayPlayOn: Boolean get() = this !is LoadRequest.Audiobook || (!fromStart && at == null)

/**
 * Where on [map], the map of the narration held, this asks to go: the sentence it names, or the one its page
 * marker is in. Null for a narration taken up where it was — a marker that names no sentence too — and for an
 * audiobook.
 */
fun LoadRequest.placeOn(map: ReadAlongMap): Long? = when (val start = (this as? LoadRequest.Narration)?.start) {
    is NarrationStart.AtSentence -> map.timeOf(start)
    is NarrationStart.AtMarker -> map.sentenceAtMarker(start.cfi)?.startMs
    NarrationStart.Resume, null -> null
}

/** Whether what is held as [source] is what this asks for: the audiobook, or the narration of the same file. */
fun LoadRequest.isFor(source: BookSource): Boolean = when (this) {
    is LoadRequest.Audiobook -> source is BookSource.Audiobook
    is LoadRequest.Narration -> source.narrationFileId == fileId
}
