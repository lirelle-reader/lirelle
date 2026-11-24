package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An audiobook's manifest as kept with its download; its content version kept under its name before 2b. */
@Serializable
internal data class StoredManifest(
    val bookId: BookId,
    @SerialName("revision") val contentVersion: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val hasCover: Boolean = false,
    val assets: List<StoredAsset> = emptyList(),
    val chapters: List<StoredChapter> = emptyList(),
    val totalDurationMs: Long,
)

@Serializable
internal data class StoredAsset(
    val assetId: String,
    val fileId: FileId,
    val sequence: Int,
    val format: String,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val etag: String? = null,
)

@Serializable
internal data class StoredChapter(
    val id: String,
    val title: String,
    val assetId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val assetOffsetMs: Long,
)

internal fun AudioManifest.stored() = StoredManifest(
    bookId = bookId,
    contentVersion = contentVersion,
    title = title,
    authors = authors,
    narrators = narrators,
    hasCover = hasCover,
    assets = assets.map {
        StoredAsset(it.assetId, it.fileId, it.sequence, it.format, it.durationMs, it.sizeBytes, it.etag)
    },
    chapters = chapters.map {
        StoredChapter(it.id, it.title, it.assetId, it.sequence, it.startMs, it.endMs, it.assetOffsetMs)
    },
    totalDurationMs = totalDurationMs,
)

internal fun StoredManifest.manifest() = AudioManifest(
    bookId = bookId,
    contentVersion = contentVersion,
    title = title,
    authors = authors,
    narrators = narrators,
    hasCover = hasCover,
    assets = assets.map {
        AudioAsset(it.assetId, it.fileId, it.sequence, it.format, it.durationMs, it.sizeBytes, it.etag)
    },
    chapters = chapters.map {
        AudioChapter(it.id, it.title, it.assetId, it.sequence, it.startMs, it.endMs, it.assetOffsetMs)
    },
    totalDurationMs = totalDurationMs,
)
