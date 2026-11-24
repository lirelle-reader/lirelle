package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.engine.isPageName
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.fileName
import java.io.File

private const val EpubDirectory = "epub"
private const val PagesDirectory = "pages"
private const val UpdateDirectory = "update"
private const val EpubSuffix = ".epub"
private const val PartSuffix = ".part"

/**
 * Makes the folders of a profile's downloads, until the profile closes. A download, a book's page kept beside
 * it or an update being swapped may end a moment after their profile: a folder made then would bring back, in
 * a profile's folder that is being deleted, the downloads of an account that left. Once [close] has returned
 * no folder is made, and what was to be written there fails as a file that could not be written.
 */
class DownloadFolders {
    private var closed = false

    /** Waits for a folder being made: none is made after this returns. */
    @Synchronized
    fun close() {
        closed = true
    }

    /** Makes [directory] if it is not there; false when nothing is to be written there: closed, or not made. */
    @Synchronized
    fun make(directory: File?): Boolean = !closed && directory != null && (directory.mkdirs() || directory.isDirectory)
}

/** Where the files of a book go: those kept, or those of an update that wait beside them. */
interface FilePlaces {
    /** What makes the folders of these places: whoever writes a file here asks it for the folder first. */
    val folders: DownloadFolders

    /** Where the finished file is. */
    fun file(bookId: BookId, format: DownloadFormat, name: String): File

    /** Where the file is while it is being received, beside [file]: its name, then a suffix. */
    fun part(bookId: BookId, format: DownloadFormat, name: String): File =
        file(bookId, format, name).let { File(it.parentFile, it.name + PartSuffix) }

    /** The room left where the files go. */
    fun freeBytes(): Long
}

/**
 * `downloads/<the book id's file name>/…` in the profile's kept folder; every path of the module goes through here.
 *
 * In a book's directory: the kept page data, one directory per format, and one for the EPUBs,
 * which the text and the narration share; and, laid out the same way under a directory of their own, the
 * files of an update until they take the place of the kept ones. A text that is not an EPUB (a PDF, a Kindle
 * book, a FictionBook) is the text's alone, and is in the text's own directory under its own extension. The
 * pages of a text made of pages, a comic or a DjVu, are in a directory of their own, each under its index.
 */
class DownloadFiles(private val root: File) : FilePlaces {
    /** One for the kept files and for those of the updates: the profile closes for both at once. */
    override val folders = DownloadFolders()

    /** Where the files of an update wait until the swap: beside the kept ones, never over them. */
    val updates: FilePlaces = object : FilePlaces {
        override val folders get() = this@DownloadFiles.folders

        override fun file(bookId: BookId, format: DownloadFormat, name: String): File =
            place(updateDir(bookId), format, name)

        override fun freeBytes(): Long = this@DownloadFiles.freeBytes()
    }

    /** The book's folder, named by its id's file name: an id is never a path. */
    fun bookDir(bookId: BookId): File = File(root, bookId.fileName)

    /**
     * Where the finished file is. The EPUB of a given name (`"<fileId>.epub"`) has one path for the
     * text and the narration: when both use it, it is there once.
     */
    override fun file(bookId: BookId, format: DownloadFormat, name: String): File = place(bookDir(bookId), format, name)

    /** The book's kept data: detail.json, cover, manifest.json, map. */
    fun kept(bookId: BookId, name: String): File = File(bookDir(bookId), name)

    /** The room left where the downloads go. */
    override fun freeBytes(): Long =
        generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0

    /**
     * Removes the files of this format, finished or not, and what an update of it fetched. An EPUB that a
     * format in [stillRecorded] uses stays: the text's while the narration is recorded with the same one, and
     * the reverse.
     */
    fun delete(bookId: BookId, format: DownloadFormat, stillRecorded: List<FormatRecord> = emptyList()) {
        val others = stillRecorded.filter { it.format != format && it.format != DownloadFormat.Audiobook }
        clear(bookDir(bookId), format, sharedEpubs(format, others.flatMap { it.files }))
        deleteUpdate(bookId, format, stillRecorded)
    }

    /**
     * Removes what an update of this format fetched; the kept files stay. An EPUB that the update of a
     * format in [stillUpdating] waits for stays.
     */
    fun deleteUpdate(bookId: BookId, format: DownloadFormat, stillUpdating: List<FormatRecord> = emptyList()) {
        val others = stillUpdating.filter { it.format != format && it.format != DownloadFormat.Audiobook }
        clear(updateDir(bookId), format, sharedEpubs(format, others.flatMap { it.update?.files.orEmpty() }))
    }

    fun deleteBook(bookId: BookId) {
        bookDir(bookId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun updateDir(bookId: BookId): File = File(bookDir(bookId), UpdateDirectory)
}

/** Where a file of an update waits until the swap: beside the kept ones, never over them. */
fun DownloadFiles.updateFile(bookId: BookId, format: DownloadFormat, name: String): File =
    updates.file(bookId, format, name)

fun DownloadFiles.updatePart(bookId: BookId, format: DownloadFormat, name: String): File =
    updates.part(bookId, format, name)

/**
 * The place of a file under [dir], a book's directory or the one of its updates. Its name says what it is: an
 * EPUB ends as one, and a page of the text is named by its index alone.
 */
private fun place(dir: File, format: DownloadFormat, name: String): File {
    val directory = when {
        format == DownloadFormat.Audiobook -> format.directory
        name.endsWith(EpubSuffix) -> EpubDirectory
        format == DownloadFormat.Text && isPageName(name) -> PagesDirectory
        else -> format.directory
    }
    return File(File(dir, directory), name)
}

/** The names of the EPUBs among [others], the files of the other formats that share theirs, which [format] may use. */
private fun sharedEpubs(format: DownloadFormat, others: List<PlannedFile>): Set<String> =
    if (format == DownloadFormat.Audiobook) {
        emptySet()
    } else {
        others.filter { it.kind == FileKind.Epub }.mapTo(mutableSetOf()) { it.name }
    }

/** Removes the files of [format] under [dir], the pages of the text with it, but for the EPUBs named in [held]. */
private fun clear(dir: File, format: DownloadFormat, held: Set<String>) {
    File(dir, format.directory).deleteRecursively()
    if (format == DownloadFormat.Audiobook) return
    if (format == DownloadFormat.Text) File(dir, PagesDirectory).deleteRecursively()
    File(dir, EpubDirectory).listFiles().orEmpty()
        // With what is beside it while it is received: its part, the version of that part.
        .filterNot { file -> held.any { file.name == it || file.name.startsWith("$it.") } }
        .forEach { it.deleteRecursively() }
}

private val DownloadFormat.directory: String
    get() = when (this) {
        DownloadFormat.Text -> "text"
        DownloadFormat.Audiobook -> "audiobook"
        DownloadFormat.Narration -> "narration"
    }
