package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest

/**
 * The map as the 3a player's manifest: one asset per played segment, in order (`tracks[i]` is asset `i`), and the
 * narrated sections as chapters.
 *
 * - An asset's id is `"<href>#<n>"`, n being the 1-based occurrence of that href among the segments; its
 *   duration is the segment's; its file is the read-along file; its etag `"<fileId>:<contentVersion>"`
 *   (`"<fileId>:"` without a version). The etag keys the audio cache with the asset id (`AudioCache.key`): two
 *   files, or two versions of one, never share entries. It is also the manifest's content version, which a narration
 *   never sends (its positions are narration positions, not playback states).
 * - An asset's format is its file's extension ("mp4", "mp3"), else the media type's subtype.
 * - A chapter's id is its section index; its title is the section's label, or empty for the screens to name it;
 *   its asset offset is from the segment's start ([ReadAlongMap.Track.clipStartMs]), as in the clipped item.
 */
fun ReadAlongMap.toManifest(
    title: String,
    authors: List<String>,
    narrators: List<String>,
    hasCover: Boolean,
): AudioManifest {
    val etag = "$fileId:${contentVersion.orEmpty()}"
    val ids = assetIds()
    val assets = tracks.mapIndexed { sequence, track ->
        AudioAsset(ids[sequence], fileId, sequence, formatOf(track), track.durationMs, track.sizeBytes, etag)
    }
    val chapters = sections.mapIndexed { sequence, section ->
        val segment = tracks.indexOfLast { it.startMs <= section.startMs }.coerceAtLeast(0)
        AudioChapter(
            id = section.index.toString(),
            title = section.title.orEmpty(),
            assetId = ids[segment],
            sequence = sequence,
            startMs = section.startMs,
            endMs = section.endMs,
            assetOffsetMs = section.startMs - tracks[segment].startMs,
        )
    }
    return AudioManifest(bookId, etag, title, authors, narrators, hasCover, assets, chapters, totalMs)
}

/** The played segment behind an asset of [toManifest]; null for an id it does not have. */
fun ReadAlongMap.trackOf(assetId: String): ReadAlongMap.Track? = assetIds().indexOf(assetId).let(tracks::getOrNull)

/** `"<href>#<n>"` for each segment, n counting the segments of that href from 1. */
private fun ReadAlongMap.assetIds(): List<String> {
    val seen = mutableMapOf<String, Int>()
    return tracks.map { track ->
        val occurrence = seen.getOrDefault(track.href, 0) + 1
        seen[track.href] = occurrence
        "${track.href}#$occurrence"
    }
}

private fun formatOf(track: ReadAlongMap.Track): String =
    track.href.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        .ifEmpty { track.mediaType.substringAfter('/') }
