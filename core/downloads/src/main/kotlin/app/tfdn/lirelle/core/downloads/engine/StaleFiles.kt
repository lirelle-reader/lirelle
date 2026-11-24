package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.FilePlaces
import java.io.File

/**
 * Removes, among the places of the files [planned] for a format, what no record says is of the version
 * planned: what an older version left there. Of a text, the pages the plan does not list go too, with what
 * was received of them: those past the count of an album that lost pages, all of them when the text is no
 * longer made of pages. It is done before a record says the files planned: from then on the record is what
 * says a file in its place is of that version, and a run that ends right after, the process with it, leaves
 * nothing the next one would take for the new version. It reads and writes the disk.
 *
 * @param known the files a record says are in their place, each with its version.
 */
internal fun FilePlaces.dropStale(
    bookId: BookId,
    format: DownloadFormat,
    planned: List<PlannedFile>,
    known: Set<Pair<File, String?>>,
) {
    planned.forEach { file ->
        val target = file(bookId, format, file.name)
        if ((target to file.version) !in known) target.delete()
    }
    if (format == DownloadFormat.Text) unlistedPages(bookId, planned).forEach { it.delete() }
}

/**
 * What is where the pages of the text of [bookId] go and is of a page not [planned]: the page, and what is
 * beside it while it is received, whose names are the page's then a suffix.
 */
private fun FilePlaces.unlistedPages(bookId: BookId, planned: List<PlannedFile>): List<File> {
    val listed = planned.filter { it.kind == FileKind.Page }.mapTo(mutableSetOf()) { it.name }
    val pages = file(bookId, DownloadFormat.Text, pageName(0)).parentFile
    return pages?.listFiles().orEmpty().filter { file ->
        val page = file.name.substringBefore('.')
        isPageName(page) && page !in listed
    }
}
