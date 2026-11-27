package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.FileId

/**
 * What a Kavita file id carries: the ids Kavita's progress is written with (library, series, volume, chapter),
 * and the file's own. Written `library-series-volume-chapter-file`, opaque to the rest of the app: a position is
 * written without asking the server where the file is.
 */
internal data class KavitaFileRef(
    val libraryId: Int,
    val seriesId: Int,
    val volumeId: Int,
    val chapterId: Int,
    val fileId: Int,
) {
    /** The app's id of the file. */
    val appId: FileId get() = FileId(listOf(libraryId, seriesId, volumeId, chapterId, fileId).joinToString(Separator))
}

/** The ids this file id carries; null for one that is not a Kavita file's. */
internal fun FileId.kavitaFile(): KavitaFileRef? {
    val numbers = value.split(Separator).map { it.kavitaNumber() ?: return null }
    if (numbers.size != Parts) return null
    // Read through an iterator: detekt refuses both a magic index and a destructuring of five.
    val next = numbers.iterator()
    return KavitaFileRef(
        libraryId = next.next(),
        seriesId = next.next(),
        volumeId = next.next(),
        chapterId = next.next(),
        fileId = next.next(),
    )
}

private const val Separator = "-"
private const val Parts = 5
