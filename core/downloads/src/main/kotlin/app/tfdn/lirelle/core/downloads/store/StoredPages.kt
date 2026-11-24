package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.engine.pageOf
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import kotlinx.serialization.Serializable

/**
 * The files of a format made of pages, in place of one entry per page: [count] pages of the file [fileId],
 * served by pages of the kind [paged] (the name of a `PagedKind`), each named by its index from 0. What else
 * a page's file says is the same for all, or is the format's: its version.
 */
@Serializable
internal data class StoredPages(val count: Int, val fileId: FileId, val paged: String)

/**
 * What stands for these files when they are exactly the pages of a count, in the [version] of their format;
 * null for any other list, which is stored entry by entry: nothing is lost either way.
 */
internal fun List<PlannedFile>.storedPages(version: String?): StoredPages? {
    val first = firstOrNull()?.let(::pageOf) ?: return null
    val pages = StoredPages(size, first.fileId, first.kind.name)
    return pages.takeIf { it.files(version) == this }
}

/**
 * The files these pages stand for; null for a kind of pages this version does not know. A count that makes
 * no sense is no page.
 */
internal fun StoredPages.files(version: String?): List<PlannedFile>? =
    pagedKind(paged)?.let { pageFiles(fileId, it, count, version) }

/** Written only for a text that has a name of its own: the others are stored as they were before. */
internal fun TextName.stored(): String? = name.takeIf { this != TextName.Text }

private const val PdfSuffix = ".pdf"

/**
 * A name this version does not know is the text. With none stored, the text is named from its [files]: a PDF
 * kept before a text had a name of its own says none, and is one by its file.
 */
internal fun textName(stored: String?, files: List<PlannedFile>): TextName = when {
    stored != null -> TextName.entries.firstOrNull { it.name == stored } ?: TextName.Text
    files.any { it.kind == FileKind.Pdf || it.name.endsWith(PdfSuffix, ignoreCase = true) } -> TextName.Pdf
    else -> TextName.Text
}
