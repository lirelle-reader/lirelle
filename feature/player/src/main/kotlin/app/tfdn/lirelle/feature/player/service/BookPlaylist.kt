package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.audio.timeline.chaptersAreParts
import app.tfdn.lirelle.core.audio.timeline.displayChapterTitles
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioManifest

/**
 * A chapter as the player shows it: its display title and its bounds on the whole book's timeline.
 *
 * @property endMs null when nobody knows where it ends (a book without chapters whose files' durations are
 * unknown).
 * @property sectionIndex a narration's chapter: its section's place in the book's reading order; null for an
 * audiobook's.
 * @property chapterId the manifest chapter's id; null for the one chapter of a book without any.
 */
data class PlaylistChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long?,
    val sectionIndex: Int? = null,
    val chapterId: String? = null,
) {
    val durationMs: Long? get() = endMs?.let { it - startMs }

    /** [endMs], or a book time never reached. */
    val endOrNever: Long get() = endMs ?: Long.MAX_VALUE

    /** [positionMs] within the chapter's bounds. */
    fun clamp(positionMs: Long): Long = durationMs?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0)
}

/** A place in the player's playlist: the file and the position inside it. */
/** How far into the next file the end of a chapter that ends with its file is reached. */
private const val NextFileLeadInMs = 1L

data class ItemPosition(val index: Int, val positionMs: Long)

/**
 * The files of a book as the engine's playlist, one [MediaItem] per file in sequence order, and the
 * chapters the player shows; maps the whole book's time to a place in the playlist and back.
 */
@OptIn(UnstableApi::class)
/**
 * @property coverUrl the cover in full, for the player screen.
 * @property artworkUrl the cover small, for the notification and the system's media controls.
 */
class BookPlaylist private constructor(
    val manifest: AudioManifest,
    val items: List<MediaItem>,
    val chapters: List<PlaylistChapter>,
    val coverUrl: String?,
    val artworkUrl: String?,
) {
    /** A narration's playlist ([ofSegments]): its chapters are narrated sections. */
    val narrated: Boolean get() = chapters.any { it.sectionIndex != null }

    /**
     * An audiobook in parts, not in chapters: none of its chapters has a title of its own ([chaptersAreParts]), and
     * [chapters] names them "Part 1", "Part 2"… A narration's sections are never parts.
     */
    val inParts: Boolean = !narrated && chaptersAreParts(manifest)

    val timeline = BookTimeline(manifest)

    /** The files, in the order of [items]. */
    val assets: List<AudioAsset> = manifest.assets.sortedBy { it.sequence }

    /** The file and the position in it of [bookTimeMs]. */
    fun itemPositionOf(bookTimeMs: Long): ItemPosition? =
        timeline.assetPositionOf(bookTimeMs)?.let { (asset, position) -> ItemPosition(assets.indexOf(asset), position) }

    /** The book time of [positionMs] in the file at [index]; null for an index the playlist does not have. */
    fun bookTimeOf(index: Int, positionMs: Long): Long? =
        assets.getOrNull(index)?.let { timeline.bookTimeOf(it.assetId, positionMs) }

    /**
     * Where the engine is to stop for the end of a chapter at [bookTimeMs]: just before it inside a file.
     * When the chapter ends with its file, a millisecond into the next file: a message at the very end of a
     * file followed by another is not reliably delivered before the engine moves on, the start of the next
     * one is (the pause lands a few milliseconds into the next chapter, as inside a file). At the end of the
     * last file, its end.
     */
    fun endPlaceOf(bookTimeMs: Long): ItemPosition? {
        val place = itemPositionOf((bookTimeMs - 1).coerceAtLeast(0)) ?: return null
        val nextStart = bookTimeOf(place.index + 1, 0)
        if (nextStart == bookTimeMs) return ItemPosition(place.index + 1, NextFileLeadInMs)
        val lastEnd = timeline.totalMs.takeIf { timeline.durationsKnown && nextStart == null }
        return if (lastEnd == bookTimeMs) ItemPosition(place.index, C.TIME_END_OF_SOURCE) else place
    }

    /** The index in [chapters] of the chapter at [bookTimeMs]. */
    fun chapterIndexAt(bookTimeMs: Long): Int = timeline.chapterAt(bookTimeMs).coerceAtLeast(0)

    companion object {
        /**
         * The playlist of [manifest]; null when it has no files or when a file has no address (nobody is
         * signed in). [partLabel] names the parts of a book in parts ([inParts]). [artworkUrl]
         * is the cover small, [coverUrl] itself unless told.
         */
        fun of(
            manifest: AudioManifest,
            coverUrl: String?,
            partLabel: (Int) -> String,
            artworkUrl: String? = coverUrl,
            assetUrl: (assetId: String) -> String?,
        ): BookPlaylist? =
            build(manifest, chaptersOf(manifest, partLabel), Covers(coverUrl, artworkUrl), { null }, assetUrl)

        /**
         * The playlist of a narration's [manifest], whose assets are segments of files: each item plays only
         * the part of its file [clip] gives (positions in it are from that part's start); the chapters keep
         * their titles, blank ones included, for the screens to name. Null as for [of].
         */
        fun ofSegments(
            manifest: AudioManifest,
            coverUrl: String?,
            clip: (AudioAsset) -> ClipRange?,
            assetUrl: (assetId: String) -> String?,
        ): BookPlaylist? = build(manifest, sectionsOf(manifest), Covers(coverUrl), clip, assetUrl)

        private fun build(
            manifest: AudioManifest,
            chapters: List<PlaylistChapter>,
            covers: Covers,
            clip: (AudioAsset) -> ClipRange?,
            assetUrl: (assetId: String) -> String?,
        ): BookPlaylist? {
            val assets = manifest.assets.sortedBy { it.sequence }
            if (assets.isEmpty()) return null
            val sorted = manifest.chapters.sortedBy { it.startMs }
            val items = assets.map { asset ->
                val url = assetUrl(asset.assetId) ?: return null
                val firstChapter = sorted.indexOfFirst { it.assetId == asset.assetId }
                itemOf(manifest, asset, url, chapters.getOrNull(firstChapter)?.title, covers.artworkUrl)
                    .withClip(clip(asset))
            }
            return BookPlaylist(manifest, items, chapters, covers.coverUrl, covers.artworkUrl)
        }

        /** With [partLabel], a book in parts shows its parts; without it, the titles stay as they are. */
        private fun chaptersOf(manifest: AudioManifest, partLabel: ((Int) -> String)?): List<PlaylistChapter> {
            val sorted = manifest.chapters.sortedBy { it.startMs }
            if (sorted.isEmpty()) {
                val timeline = BookTimeline(manifest)
                return listOf(PlaylistChapter(manifest.title, 0, timeline.totalMs.takeIf { timeline.durationsKnown }))
            }
            val titles = partLabel?.let { displayChapterTitles(manifest, it) } ?: sorted.map { it.title }
            return sorted.mapIndexed { index, chapter ->
                PlaylistChapter(
                    titles[index],
                    chapter.startMs,
                    chapter.endMs.coerceAtLeast(chapter.startMs),
                    chapterId = chapter.id,
                )
            }
        }

        /** A narration's chapters, each with its section's place in the book (the manifest chapter's id). */
        private fun sectionsOf(manifest: AudioManifest): List<PlaylistChapter> {
            val sorted = manifest.chapters.sortedBy { it.startMs }
            return chaptersOf(manifest, partLabel = null).mapIndexed { index, chapter ->
                chapter.copy(sectionIndex = sorted.getOrNull(index)?.id?.toIntOrNull())
            }
        }

        private fun itemOf(manifest: AudioManifest, asset: AudioAsset, url: String, chapter: String?, cover: String?) =
            MediaItem.Builder()
                .setMediaId(asset.assetId)
                .setUri(url)
                .setCustomCacheKey(AudioCache.key(asset.assetId, asset.etag))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(manifest.title)
                        .setArtist(chapter)
                        .setArtworkUri(cover?.let(Uri::parse))
                        .build(),
                )
                .build()

        private fun MediaItem.withClip(range: ClipRange?): MediaItem = range?.let {
            buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(it.startMs)
                        .setEndPositionMs(it.endMs)
                        .build(),
                )
                .build()
        } ?: this
    }
}

/** The part of a file an item plays, in ms from the file's start. */
data class ClipRange(val startMs: Long, val endMs: Long)

/** The cover in full, and small. */
private data class Covers(val coverUrl: String?, val artworkUrl: String? = coverUrl)
