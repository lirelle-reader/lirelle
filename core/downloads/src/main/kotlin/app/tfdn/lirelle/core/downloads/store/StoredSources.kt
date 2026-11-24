package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import kotlinx.serialization.Serializable

private const val Whole = "Whole"
private const val Audioless = "Audioless"
private const val Page = "Page"
private const val AudioAsset = "AudioAsset"
private const val ReadAlongTrack = "ReadAlongTrack"

/**
 * A [FileSource] as it is written: its [type], then what that type names, the others absent. Flat rather than
 * polymorphic, so that a type this version does not know reads as an unknown source, not as a registry that
 * no longer parses. [paged] is the name of a [PagedKind]. No address: see [FileSource].
 */
@Serializable
internal data class StoredSource(
    val type: String,
    val fileId: FileId? = null,
    val paged: String? = null,
    val index: Int? = null,
    val assetId: String? = null,
    val href: String? = null,
)

internal fun FileSource.stored(): StoredSource = when (this) {
    is FileSource.Whole -> StoredSource(Whole, fileId = fileId)
    is FileSource.Audioless -> StoredSource(Audioless, fileId = fileId)
    is FileSource.Page -> StoredSource(Page, fileId = fileId, paged = kind.name, index = index)
    is FileSource.AudioAsset -> StoredSource(AudioAsset, assetId = assetId)
    is FileSource.ReadAlongTrack -> StoredSource(ReadAlongTrack, fileId = fileId, href = href)
}

/** Null for a type this version does not know, or one written without what it names. */
internal fun StoredSource.source(): FileSource? = when (type) {
    Whole -> fileId?.let(FileSource::Whole)
    Audioless -> fileId?.let(FileSource::Audioless)
    Page -> page()
    AudioAsset -> assetId?.let(FileSource::AudioAsset)
    ReadAlongTrack -> if (fileId != null && href != null) FileSource.ReadAlongTrack(fileId, href) else null
    else -> null
}

private fun StoredSource.page(): FileSource.Page? {
    val kind = pagedKind(paged) ?: return null
    return if (fileId != null && index != null) FileSource.Page(fileId, kind, index) else null
}

/** The [PagedKind] of this name; null for one this version does not know. */
internal fun pagedKind(name: String?): PagedKind? = PagedKind.entries.firstOrNull { it.name == name }
