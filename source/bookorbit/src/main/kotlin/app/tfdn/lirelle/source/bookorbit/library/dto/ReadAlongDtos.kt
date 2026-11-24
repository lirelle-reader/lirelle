package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable

// The read-along routes: `epub/{bookId}/media-overlay` (the parsed sync map) and `epub/{bookId}/info`.
// Only the fields that are read are declared; the others are ignored.

@Serializable
internal data class OverlayItemDto(
    val sectionIndex: Int,
    val textHref: String,
    val textFragment: String? = null,
    val audioHref: String,
    val clipBeginSeconds: Double = 0.0,
    val clipEndSeconds: Double? = null,
)

@Serializable
internal data class OverlaySectionDto(val index: Int, val href: String, val label: String? = null)

@Serializable
internal data class OverlayResourceDto(val href: String, val mediaType: String = "", val size: Long? = null)

@Serializable
internal data class MediaOverlayMapDto(
    val bookId: Long,
    val fileId: Long,
    val durationSeconds: Double? = null,
    val items: List<OverlayItemDto> = emptyList(),
    val sections: List<OverlaySectionDto> = emptyList(),
    val resources: List<OverlayResourceDto> = emptyList(),
)

@Serializable
internal data class SpineItemDto(val idref: String, val href: String)

@Serializable
internal data class ManifestItemDto(val id: String, val size: Long? = null)

@Serializable
internal data class EpubInfoDto(
    val spine: List<SpineItemDto> = emptyList(),
    val manifest: List<ManifestItemDto> = emptyList(),
)
