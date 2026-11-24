package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.pageName
import app.tfdn.lirelle.core.downloads.engine.wholeFileOf
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.library.offline.KeptPages
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The files of the formats the registry says complete, and not being deleted. A file that is no longer on the
 * device is not given.
 *
 * The audio files are found in memory, by their address, which the player asks them by: the registry keeps
 * none, and each is asked of [locations] as the registry changes. Nothing is known of them until [start] has
 * read the registry once, which is done as the profile opens.
 */
@ProfileScoped
class RegistryDownloadedFiles @Inject constructor(
    private val registry: DownloadRegistry,
    private val files: DownloadFiles,
    private val locations: DownloadLocations,
) : DownloadedFiles {
    @Volatile
    private var audio: Map<String, File> = emptyMap()

    /** Keeps the audio files known as the registry changes, for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch { registry.books.collect { audio = audioFiles(it) } }
    }

    override suspend fun text(bookId: BookId, fileId: FileId): File? {
        // By the file it is drawn from, whatever its name and its extension: `12.epub`, `12.pdf`.
        val kept = registry.book(bookId)?.formats.orEmpty().filter { it.isUsable }.firstNotNullOfOrNull { record ->
            record.files.firstOrNull { wholeFileOf(it) == fileId }?.let { record.format to it }
        }
        return kept?.let { (format, file) -> files.file(bookId, format, file.name) }?.takeIf { it.isFile }
    }

    /**
     * The pages are the text's own, and no other format's. Every one of them is looked for: an album a page of
     * which went is not whole, and is read as one the device does not keep.
     */
    override suspend fun pages(bookId: BookId, fileId: FileId): KeptPages? {
        val record = registry.book(bookId)?.formats.orEmpty()
            .firstOrNull { it.format == DownloadFormat.Text && it.isUsable && it.fileId == fileId }
        val count = record?.pages?.total ?: return null
        val kept = KeptPages(count) { index -> files.file(bookId, DownloadFormat.Text, pageName(index)) }
        return kept.takeIf { pages -> (0 until count).all { pages.file(it).isFile } }
    }

    override fun audio(url: String): File? = audio[addressKey(url)]?.takeIf { it.isFile }

    private fun audioFiles(books: List<DownloadedBook>): Map<String, File> = buildMap {
        for (book in books) {
            for (record in book.formats.filter { it.isUsable }) {
                for (file in record.files.filter { it.kind == FileKind.Audio }) {
                    val url = locations.url(book.bookId, file.source) ?: continue
                    put(addressKey(url), files.file(book.bookId, record.format, file.name))
                }
            }
        }
    }
}

private val FormatRecord.isUsable: Boolean get() = !removing && state is FormatState.Complete

/** The address with its query parameters in one order, whatever the order they were written in. */
private fun addressKey(url: String): String {
    val path = url.substringBefore('?')
    val query = url.substringAfter('?', missingDelimiterValue = "")
    return if (query.isEmpty()) path else "$path?${query.split('&').sorted().joinToString("&")}"
}
