package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.reader.archive.EpubArchive
import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import java.io.File

private val KindleFormats = setOf("MOBI", "AZW", "AZW3")
private const val FictionBookFormat = "FB2"

/** What the page reads a book from, and the [format] it is told to open it with. */
sealed interface BookContent {
    val format: BookFormat

    /** An EPUB, checked and open: the page asks for its entries one by one. */
    data class Epub(val archive: EpubArchive) : BookContent {
        override val format get() = BookFormat.Epub
    }

    /**
     * A Kindle book or a FictionBook: [file] is given to the page whole, and the engine says whether it can
     * read it. Nothing is checked on the device.
     */
    data class Whole(val file: File, override val format: BookFormat) : BookContent
}

/**
 * How the page opens a file the book reader lays out: a MOBI, an AZW and an AZW3 are all Kindle books, which
 * the engine tells apart itself; whatever is neither one of them nor a FictionBook is taken as an EPUB.
 */
internal val BookFile.pageFormat: BookFormat
    get() = when (format.uppercase()) {
        in KindleFormats -> BookFormat.Mobi
        FictionBookFormat -> BookFormat.Fb2
        else -> BookFormat.Epub
    }
