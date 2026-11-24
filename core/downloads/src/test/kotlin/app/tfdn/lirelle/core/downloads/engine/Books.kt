package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.testing.sampleBookDetail

/**
 * Invented books, one per shape a download meets. A file's [id] is a short number for the tests to write: the
 * file is named by the id whose text it is, as a BookOrbit file would be.
 */
internal object Books {
    fun epub(id: Long, sizeBytes: Long? = 1_200_000, primary: Boolean = true, format: String = "EPUB") =
        BookFile(fileIdOf(id), BookFileKind.Text, format, sizeBytes, primary, null, null, "text-$id")

    fun readAlong(id: Long, sizeBytes: Long? = 98_700_000, primary: Boolean = true) =
        BookFile(fileIdOf(id), BookFileKind.ReadAlongEpub, "EPUB", sizeBytes, primary, 36_000, null, "along-$id")

    fun audio(id: Long, sizeBytes: Long?, chapters: Int? = null, format: String = "MP3") =
        BookFile(fileIdOf(id), BookFileKind.Audio, format, sizeBytes, false, 3_600, chapters, "audio-$id")

    fun book(vararg files: BookFile, id: String = "3"): BookDetail = sampleBookDetail(id).copy(files = files.toList())

    private fun fileIdOf(id: Long) = FileId(id.toString())
}
