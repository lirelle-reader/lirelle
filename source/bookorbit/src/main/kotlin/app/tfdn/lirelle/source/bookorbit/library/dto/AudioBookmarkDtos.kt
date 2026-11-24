package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable

// The audiobook bookmark routes: `audiobooks/{bookId}/bookmarks`. A request type declares only what
// is sent: the server refuses a request body with a field it does not know.

@Serializable
internal data class AudioBookmarkDto(
    val id: String,
    val bookId: Long,
    val positionMs: Long = 0,
    val chapterId: String? = null,
    val title: String = "",
    val note: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** [chapterId] and [note] are left out when null. Posting the same [clientId] again answers the stored one. */
@Serializable
internal data class AudioBookmarkWriteDto(
    val clientId: String,
    val positionMs: Long,
    val chapterId: String? = null,
    val title: String,
    val note: String? = null,
)
