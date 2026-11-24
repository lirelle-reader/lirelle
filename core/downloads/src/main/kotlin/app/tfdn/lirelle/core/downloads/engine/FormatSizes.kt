package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.FilePlaces

/**
 * The files [format] counts as its own among those [planned]: all of them, but for the narration the EPUB
 * the text of the same [book] counts already, so that no file of a book is counted twice.
 */
internal fun countedFiles(
    book: DownloadedBook?,
    format: DownloadFormat,
    planned: List<PlannedFile>,
): List<PlannedFile> {
    val text = book?.formats?.firstOrNull { it.format == DownloadFormat.Text }
    if (format != DownloadFormat.Narration || text == null) return planned
    val names = text.files.map { it.name }
    // A text not planned yet will be made of the same EPUB.
    return planned.filterNot { it.kind == FileKind.Epub && (names.isEmpty() || it.name in names) }
}

/**
 * What the device has of one file.
 *
 * @property bytes those it has of it, whole or on its way.
 * @property size what it takes once whole: its length when it is, else the size planned, null when none is.
 */
internal class FileSeen(val bytes: Long, val size: Long?)

/**
 * What the device has of the files a format counts, where [files] puts them. It reads the disk: call it off
 * the main thread.
 *
 * @property counted the files the format counts as its own.
 * @property estimate about what the files take together, for files that say no size of their own: the pages
 *   of a text, whose file's size it is. Null when there is none.
 */
internal class FormatSizes(
    private val files: FilePlaces,
    private val bookId: BookId,
    private val format: DownloadFormat,
    val counted: List<PlannedFile>,
    val estimate: Long? = null,
) {
    /** What the device has of [file] now. */
    fun seen(file: PlannedFile): FileSeen {
        val whole = files.file(bookId, format, file.name).takeIf { it.isFile }?.length()
        return FileSeen(whole ?: files.part(bookId, format, file.name).length(), whole ?: file.expectedBytes)
    }

    /** The bytes on the device, of whole files and of those on their way. */
    fun received(): Long = counted.sumOf { seen(it).bytes }
}

/**
 * What a run has of the files its format counts: read from the device once, with [count], when the run
 * starts, then file by file, with [look], as each one arrives. An album is then fetched without all its
 * pages being looked at again for each of them. It reads the disk: call [count] and [look] off the main
 * thread.
 */
internal class RunningSizes(private val sizes: FormatSizes) {
    private val names = sizes.counted.mapTo(HashSet()) { it.name }
    private val seen = mutableMapOf<String, FileSeen>()

    /** The files not whole that say no size. */
    private var unsized = 0

    /** What the others take once whole. */
    private var sized = 0L

    /** The bytes on the device, of whole files and of those on their way. */
    var received = 0L
        private set

    /** Reads what the device has of every file counted. */
    fun count() = sizes.counted.forEach(::look)

    /** Reads again what the device has of [file], once it was fetched, whole or in part, if it is counted. */
    fun look(file: PlannedFile) {
        if (file.name !in names) return
        seen[file.name]?.let { tally(it, -1) }
        seen[file.name] = sizes.seen(file).also { tally(it, 1) }
    }

    private fun tally(file: FileSeen, sign: Int) {
        received += sign * file.bytes
        if (file.size == null) unsized += sign else sized += sign * file.size
    }

    /** What the device has of the files counted but [file]; null when [file] is not one of them. */
    fun without(file: PlannedFile): Long? = seen[file.name]?.let { received - it.bytes }

    /**
     * What the files will take once whole. While the size of one is not known it is the estimate, never less
     * than what the device already has, or null without one.
     */
    fun total(): Long? = if (unsized > 0) sizes.estimate?.coerceAtLeast(received) else sized

    /** What is left of the estimate once what the device has is taken off; nothing without an estimate. */
    fun estimatedLeft(): Long = sizes.estimate?.minus(received)?.coerceAtLeast(0) ?: 0
}

/** The bytes received that a state speaks of. */
internal val FormatState.received: Long
    get() = when (this) {
        FormatState.Queued -> 0
        is FormatState.Downloading -> receivedBytes
        is FormatState.Waiting -> receivedBytes
        is FormatState.Failed -> receivedBytes
        is FormatState.Complete -> bytes
    }
