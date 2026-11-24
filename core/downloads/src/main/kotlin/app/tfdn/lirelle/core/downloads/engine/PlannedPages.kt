package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind

/** The name of a page on the device: its index, from 0. No other file of a text has a name made of digits only. */
internal fun pageName(index: Int): String = index.toString()

/** Whether [name] is a page's: see [pageName]. */
internal fun isPageName(name: String): Boolean = name.isNotEmpty() && name.all { it in '0'..'9' }

/** The page this file is, when it is one: what it is drawn from and its index; null for a file that is no page. */
internal fun pageOf(file: PlannedFile): FileSource.Page? =
    (file.source as? FileSource.Page)?.takeIf { file.kind == FileKind.Page }

/**
 * The file these files are the text of, when one of them says it: by what it is drawn from, a single file or a
 * page. Null when none does.
 */
internal fun textFileOf(files: List<PlannedFile>): FileId? =
    files.firstOrNull { it.kind.isText }?.let { wholeFileOf(it) ?: pageOf(it)?.fileId }

/**
 * The files of a text made of [count] pages of the file [fileId], served by pages of this [kind], in reading
 * order. The server says no size for a page and answers no range for it; all are of the [version] of the file
 * they are drawn from. A record whose files are exactly these is stored as their count.
 */
internal fun pageFiles(fileId: FileId, kind: PagedKind, count: Int, version: String?): List<PlannedFile> =
    List(count.coerceAtLeast(0)) { index ->
        PlannedFile(
            name = pageName(index),
            source = FileSource.Page(fileId, kind, index),
            version = version,
            expectedBytes = null,
            resumable = false,
            kind = FileKind.Page,
        )
    }
