package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import java.time.Instant

/** Where a mark was made. An origin the app does not know is [Web]. */
enum class MarkOrigin { Web, KoReader, Kobo }

/** How a highlight is drawn. A style the app does not know is [Highlight]. */
enum class HighlightStyle { Highlight, Underline, Strikethrough, Squiggly, Invert }

/** How well the server could place a highlight in the file. */
enum class PositionStatus { Exact, Repaired, Pending, Failed }

/** [cfi] is null when the bookmark cannot be placed in the file that was asked for. */
data class ServerBookmark(
    val id: MarkId,
    val cfi: String?,
    val title: String,
    val note: String?,
    val origin: MarkOrigin,
    val createdAt: Instant,
)

/**
 * [cfi] is null for a highlight that cannot be placed in the file. [color] is kept as the server
 * holds it: usually `#RRGGBB`, but a legacy name such as `yellow` occurs. [createdAt] is when the
 * text was highlighted (the server's `highlightedAt`, which a device import sets to the device's
 * time), else when the row was made.
 */
data class ServerHighlight(
    val id: MarkId,
    val cfi: String?,
    val text: String,
    val color: String,
    val style: HighlightStyle,
    val note: String?,
    val chapterTitle: String?,
    val chapterIndex: Int?,
    val origin: MarkOrigin,
    val positionStatus: PositionStatus?,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

/** The style is always `highlight`. */
data class NewHighlight(
    val cfi: String,
    val fileId: FileId,
    val text: String,
    val color: String,
    val note: String?,
    val chapterTitle: String?,
)
