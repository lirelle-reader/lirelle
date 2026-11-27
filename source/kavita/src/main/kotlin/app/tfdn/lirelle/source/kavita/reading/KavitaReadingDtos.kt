package app.tfdn.lirelle.source.kavita.reading

import kotlinx.serialization.Serializable

/** A chapter's place as Kavita keeps it (`GET api/Reader/get-progress`). */
@Serializable
internal data class ProgressDto(
    val volumeId: Int = 0,
    val chapterId: Int = 0,
    val pageNum: Int = 0,
    val seriesId: Int = 0,
    val libraryId: Int = 0,
    val bookScrollId: String? = null,
    val lastModifiedUtc: String? = null,
)

/**
 * A chapter's place as the app writes it (`POST api/Reader/progress`). Every field is always sent, a null
 * [bookScrollId] too: Kavita requires the page and the four ids, and refuses a place without them (400), even one
 * at page 0. No date: Kavita dates the place itself.
 */
@Serializable
internal data class ProgressWriteDto(
    val volumeId: Int,
    val chapterId: Int,
    val pageNum: Int,
    val seriesId: Int,
    val libraryId: Int,
    val bookScrollId: String?,
)

/** What `GET api/Reader/chapter-info` says of a chapter, of which the page count is read. */
@Serializable
internal data class ChapterInfoDto(val pages: Int = 0)
