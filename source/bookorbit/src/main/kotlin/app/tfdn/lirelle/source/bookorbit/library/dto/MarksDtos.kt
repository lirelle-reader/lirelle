package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable

// Written from the shapes the server answers on its annotation and bookmark routes. The server refuses a
// request body with a field it does not know: a request type declares only what is sent. Answers are read
// leniently: origin, style and status stay strings, and every optional key may be missing (the anonymiser
// of the recorded fixtures drops some).

@Serializable
internal data class BookmarkDto(
    val id: Long,
    val cfi: String? = null,
    val title: String = "",
    val note: String? = null,
    val origin: String? = null,
    val createdAt: String? = null,
)

@Serializable
internal data class HighlightDto(
    val id: Long,
    val cfi: String? = null,
    val text: String = "",
    val color: String = "",
    val style: String? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
    val chapterIndex: Int? = null,
    val origin: String? = null,
    val positionStatus: String? = null,
    val createdAt: String? = null,
    val highlightedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class BookmarkWriteDto(val cfi: String, val title: String, val bookFileId: Long)

/** A null [note] or [chapterTitle] is left out. */
@Serializable
internal data class HighlightWriteDto(
    val cfi: String,
    val bookFileId: Long,
    val text: String,
    val color: String,
    val style: String = "highlight",
    val note: String? = null,
    val chapterTitle: String? = null,
)
