package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stands for null in the number arrays. */
private const val None = -1L

/** The stored form's version: another one reads as missing. */
private const val StoredFormat = 1

private val StoredJson = Json { ignoreUnknownKeys = true }

/**
 * A read-along file's playlist and spine as kept on the device: parallel arrays, the hrefs written once each,
 * the whole gzipped. Numbers that may be missing are -1.
 */
@Serializable
internal class StoredReadAlong(
    val format: Int,
    val bookId: BookId,
    val fileId: FileId,
    val contentVersion: String?,
    val durationMs: Long,
    val items: StoredItems,
    val sections: StoredSections,
    val resources: StoredResources,
    val spine: StoredSpine,
)

/** [text] and [audio] point into [hrefs]. */
@Serializable
internal class StoredItems(
    val hrefs: List<String>,
    val section: IntArray,
    val text: IntArray,
    val fragment: List<String?>,
    val audio: IntArray,
    val beginMs: LongArray,
    val endMs: LongArray,
)

@Serializable
internal class StoredSections(val index: IntArray, val href: List<String>, val label: List<String?>)

@Serializable
internal class StoredResources(val href: List<String>, val mediaType: List<String>, val sizeBytes: LongArray)

@Serializable
internal class StoredSpine(val index: IntArray, val href: List<String>, val sizeBytes: LongArray)

/** Kept under [fileId] and [contentVersion], those asked for. */
internal fun storedReadAlong(
    fileId: FileId,
    contentVersion: String?,
    playlist: ReadAlongPlaylist,
    spine: List<SpineEntry>,
) = StoredReadAlong(
    format = StoredFormat,
    bookId = playlist.bookId,
    fileId = fileId,
    contentVersion = contentVersion,
    durationMs = playlist.durationMs ?: None,
    items = storedItems(playlist.items),
    sections = playlist.sections.let { list ->
        StoredSections(list.map { it.index }.toIntArray(), list.map { it.href }, list.map { it.label })
    },
    resources = playlist.resources.let { list ->
        StoredResources(list.map { it.href }, list.map { it.mediaType }, list.longs { it.sizeBytes })
    },
    spine = StoredSpine(spine.map { it.index }.toIntArray(), spine.map { it.href }, spine.longs { it.sizeBytes }),
)

private fun storedItems(items: List<OverlayItem>): StoredItems {
    val hrefs = (items.map { it.textHref } + items.map { it.audioHref }).distinct()
    val place = hrefs.withIndex().associate { (index, href) -> href to index }
    return StoredItems(
        hrefs = hrefs,
        section = items.map { it.sectionIndex }.toIntArray(),
        text = items.map { place.getValue(it.textHref) }.toIntArray(),
        fragment = items.map { it.fragment },
        audio = items.map { place.getValue(it.audioHref) }.toIntArray(),
        beginMs = items.map { it.clipBeginMs }.toLongArray(),
        endMs = items.longs { it.clipEndMs },
    )
}

private fun <T> List<T>.longs(value: (T) -> Long?): LongArray = LongArray(size) { value(this[it]) ?: None }

private fun Long.orNull(): Long? = takeIf { it != None }

/** The playlist, or null when this stored form is not one this version reads, or does not hold together. */
internal fun StoredReadAlong.playlist(): ReadAlongPlaylist? {
    if (format != StoredFormat) return null
    val overlayItems = with(items) {
        require(listOf(text.size, fragment.size, audio.size, beginMs.size, endMs.size).all { it == section.size })
        section.indices.map { index ->
            val (textHref, audioHref) = hrefs[text[index]] to hrefs[audio[index]]
            OverlayItem(section[index], textHref, fragment[index], audioHref, beginMs[index], endMs[index].orNull())
        }
    }
    val overlaySections = with(sections) { index.indices.map { OverlaySection(index[it], href[it], label[it]) } }
    val overlayResources = with(resources) {
        href.indices.map { OverlayResource(href[it], mediaType[it], sizeBytes[it].orNull()) }
    }
    return ReadAlongPlaylist(bookId, fileId, durationMs.orNull(), overlayItems, overlaySections, overlayResources)
}

internal fun StoredReadAlong.spineEntries(): List<SpineEntry> =
    with(spine) { index.indices.map { SpineEntry(index[it], href[it], sizeBytes[it].orNull()) } }

internal fun StoredReadAlong.writeTo(output: OutputStream) = GZIPOutputStream(output).bufferedWriter().use {
    it.write(StoredJson.encodeToString(StoredReadAlong.serializer(), this))
}

internal fun readStoredReadAlong(input: InputStream): StoredReadAlong = GZIPInputStream(input).bufferedReader().use {
    StoredJson.decodeFromString(StoredReadAlong.serializer(), it.readText())
}
