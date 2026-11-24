package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.ids.fileNameOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val Albums = 300
private const val PartPrefix = "zones"
private const val PartSuffix = ".part"

/** How old, in milliseconds, a file being written is when it is taken for what a crash left behind. */
private const val OrphanMillis = 60_000L

/** The longest name an album's file is given; beyond, the version of its content is written as its hash. */
private const val LongestName = 200

/**
 * What stands for the version of the content in a file's name when it is not written as [fileNameOf] makes it.
 * That one follows its `~` by two upper-case hex digits, and never gives an empty name: none of these can be the
 * name of a version.
 */
private const val NoVersion = "~none"
private const val EmptyVersion = "~empty"
private const val HashedVersion = "~sha"
private const val HashLength = 32

/** Between the parts of a file's name: [fileNameOf] never writes a dot, so no part runs into the next. */
private const val Separator = "."
private const val FilledMark = "P"
private const val SparseMark = "W"

/** A page's line: its number, the length of its picture, its zones. */
private const val LineParts = 3

/** A zone: its four sides and its kind. */
private const val ZoneParts = 5

/** What is kept of a page: its zones, and the length of the picture they were found on. */
internal data class KeptZones(val pictureBytes: Long, val zones: List<Zone>)

/**
 * The zones found, kept in [directory], a folder of the profile's cache: a file per album file, version of its
 * content, reading direction and version of the cut, under a name no other of these shares. The system may empty
 * it, and it goes with the profile: zones are found again. The [limit] albums last written stay. Once the profile
 * closed ([close]) nothing more is kept there. Not for the main thread.
 *
 * Whatever the number of stores on one folder, one of them at a time reads or writes there: a page kept by one is
 * not lost by another keeping a page of the same album at the same moment.
 *
 * @param cutVersion the version of the cut whose zones these are.
 * @param now the time, in milliseconds, an album's file is said written at.
 */
internal class ZoneStore(
    private val directory: File,
    private val cutVersion: Int = CutVersion,
    private val limit: Int = Albums,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var closed = false

    /** What all the stores on this folder take turns on; found when first needed, as it asks the file system. */
    private val turn: Any by lazy { turns.computeIfAbsent(canonical(directory)) { Any() } }

    /**
     * What is kept of the pages of that album read in that direction, by page; empty when nothing can be read. A
     * line that is not a page's, or that holds a zone that cannot be one, is passed over: the other pages stay.
     */
    fun of(key: ComicKey, direction: Direction): Map<Int, KeptZones> = synchronized(turn) { read(file(key, direction)) }

    /**
     * The profile closes: nothing is kept from now on, and the folder is not made again. A reader outlives its
     * profile by a moment, and zones found then would make the folder again in a profile's folder that is being
     * deleted. Waits for zones being written; what is there is still read.
     */
    fun close() {
        synchronized(turn) { closed = true }
    }

    /**
     * Keeps the [zones] of the page [index], with what was kept of the album's other pages; what was kept of that
     * page before is replaced. Nothing is kept once the profile closed.
     */
    fun keep(key: ComicKey, direction: Direction, index: Int, pictureBytes: Long, zones: List<Zone>) {
        synchronized(turn) {
            if (closed) return
            try {
                if (!directory.mkdirs() && !directory.isDirectory) return
                val target = file(key, direction)
                val at = now()
                write(target, read(target) + (index to KeptZones(pictureBytes, zones)), at)
                trim(target, at)
            } catch (_: IOException) {
                // Not kept: the page is cut again the next time.
            }
        }
    }

    /**
     * Written whole beside its place, under a name of its own, then put there: a file half written is never read,
     * and two writers never share one.
     */
    private fun write(target: File, pages: Map<Int, KeptZones>, at: Long) {
        val part = File.createTempFile(PartPrefix, PartSuffix, directory)
        try {
            part.writeText(pages.toSortedMap().entries.joinToString("\n") { (page, kept) -> line(page, kept) })
            if (part.renameTo(target)) target.setLastModified(at)
        } finally {
            // Gone already when it was put in its place.
            part.delete()
        }
    }

    /**
     * Only the [limit] albums last written stay, the one just [written] among them whatever its time; and what a
     * crash left of a file being written goes, once old enough at the time [at] not to be one being written.
     */
    private fun trim(written: File, at: Long) {
        val (parts, albums) = directory.listFiles().orEmpty().filter { it.isFile }
            .partition { it.name.endsWith(PartSuffix) }
        parts.filter { at - it.lastModified() > OrphanMillis }.forEach { it.delete() }
        albums.filter { it != written }.sortedByDescending { it.lastModified() }
            .drop((limit - 1).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun file(key: ComicKey, direction: Direction): File {
        val way = when (direction) {
            Direction.LeftToRight -> "ltr"
            Direction.RightToLeft -> "rtl"
        }
        fun name(version: String) = listOf(key.fileId.fileName, version, way, "c$cutVersion").joinToString(Separator)
        val plain = name(versionName(key.contentVersion))
        // A hash stands for a version too long to be written: a name the file system does not take keeps nothing.
        return File(directory, if (plain.length <= LongestName) plain else name(hashed(key.contentVersion.orEmpty())))
    }

    private companion object {
        /** By folder, what its stores take turns on. */
        val turns = ConcurrentHashMap<String, Any>()
    }
}

/** The folder under the one name all its stores know it by. */
private fun canonical(directory: File): String = try {
    directory.canonicalPath
} catch (_: IOException) {
    directory.absolutePath
}

/** The [version] of a content as a part of a file's name, two versions never sharing one. */
private fun versionName(version: String?): String = when {
    version == null -> NoVersion
    version.isEmpty() -> EmptyVersion
    else -> fileNameOf(version)
}

private fun hashed(version: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(version.encodeToByteArray())
    return HashedVersion + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(HashLength)
}

/** What the [file] keeps, by page; the lines that cannot be read are passed over. */
private fun read(file: File): Map<Int, KeptZones> = try {
    if (file.isFile) file.readLines().mapNotNull(::parse).toMap() else emptyMap()
} catch (_: IOException) {
    emptyMap()
}

private fun line(page: Int, kept: KeptZones): String {
    val zones = kept.zones.joinToString(" ") { zone ->
        val mark = when (zone.kind) {
            ZoneKind.Filled -> FilledMark
            ZoneKind.Sparse -> SparseMark
        }
        "${zone.left},${zone.top},${zone.right},${zone.bottom},$mark"
    }
    return "$page;${kept.pictureBytes};$zones"
}

/** The page of a [line] and what is kept of it; null when the line is not one this store writes. */
private fun parse(line: String): Pair<Int, KeptZones>? = try {
    val parts = line.split(';')
    require(parts.size == LineParts) { "not a page's line" }
    val (page, bytes, zones) = parts
    val index = page.toInt()
    require(index >= 0) { "not a page" }
    index to KeptZones(bytes.toLong(), zones.split(' ').filter { it.isNotBlank() }.map(::zone))
} catch (_: IllegalArgumentException) {
    null
}

/** The zone written as [text]; throws [IllegalArgumentException] when it is none, or does not lie in a page. */
private fun zone(text: String): Zone {
    val parts = text.split(',')
    require(parts.size == ZoneParts) { "not a zone" }
    val kind = when (parts.last()) {
        FilledMark -> ZoneKind.Filled
        SparseMark -> ZoneKind.Sparse
        else -> throw IllegalArgumentException("unknown kind")
    }
    // In the order they are written: left, top, right, bottom.
    val sides = parts.dropLast(1).map { it.toFloat() }.iterator()
    val zone = Zone(sides.next(), sides.next(), sides.next(), sides.next(), kind)
    // Written this way round so that a side that is no number at all fails too.
    require(zone.left >= 0f && zone.left < zone.right && zone.right <= 1f) { "not across a page" }
    require(zone.top >= 0f && zone.top < zone.bottom && zone.bottom <= 1f) { "not down a page" }
    return zone
}
