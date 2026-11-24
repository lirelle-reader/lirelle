package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.reader.archive.EpubCheckResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.archive.checkEpub
import java.io.File
import java.io.IOException

/** What every PDF starts with. */
private val PdfMark = "%PDF".toByteArray(Charsets.US_ASCII)

/** What a JPEG, a PNG, a GIF and a BMP start with. */
private val PictureMarks = listOf(
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    "GIF8".toByteArray(Charsets.US_ASCII),
    "BM".toByteArray(Charsets.US_ASCII),
)

/** A WebP is a RIFF file, which says after its length that it is a WebP. */
private val RiffMark = "RIFF".toByteArray(Charsets.US_ASCII)
private val WebpMark = "WEBP".toByteArray(Charsets.US_ASCII)
private const val WebpMarkAt = 8
private const val PictureStart = 12

/**
 * An AVIF and a HEIF are files of the ISO family: after the length of their first box comes its mark, then the
 * brand, which says a picture, one or a sequence, or anything else of the family: a film, a sound.
 */
private val IsoMark = "ftyp".toByteArray(Charsets.US_ASCII)
private const val IsoMarkAt = 4
private const val IsoBrandAt = 8
private val PictureBrands = listOf("avif", "avis", "heic", "heix", "hevc", "mif1", "msf1")
    .map { it.toByteArray(Charsets.US_ASCII) }

/**
 * Whether [file], received whole, is what this kind says: an EPUB by the reader's check, a PDF and a page by
 * their first bytes. The other kinds are taken as they come: an audio file is the player's to judge, and a
 * Kindle book or a FictionBook the reader's.
 */
internal fun FileKind.isKindOf(file: File): Boolean = when (this) {
    FileKind.Epub -> isEpub(file)
    FileKind.Pdf -> isPdf(file)
    FileKind.Page -> isPicture(file)
    FileKind.Audio, FileKind.Book -> true
}

/** A protected EPUB is one: it is kept, and the reader says what it is when it opens it. */
private fun isEpub(file: File): Boolean = when (val check = checkEpub(file)) {
    EpubCheckResult.Ok -> true
    is EpubCheckResult.Refused -> check.problem == EpubProblem.Protected
}

/**
 * A page is a picture, as the server serves it or draws it, in any format the page reader draws: a JPEG, a
 * PNG, a GIF, a BMP, a WebP, an AVIF or a HEIF by its first bytes, and not empty. It is not decoded: the page
 * reader says what it makes of it. What the server answers in the place of a picture, an error in text, is no
 * page.
 */
private fun isPicture(file: File): Boolean = try {
    val start = file.inputStream().use { it.readNBytes(PictureStart) }
    val webp = start.startsWith(RiffMark) && start.startsWith(WebpMark, at = WebpMarkAt)
    webp || isIsoPicture(start) || PictureMarks.any { start.startsWith(it) }
} catch (_: IOException) {
    false
}

private fun isIsoPicture(start: ByteArray): Boolean =
    start.startsWith(IsoMark, at = IsoMarkAt) && PictureBrands.any { start.startsWith(it, at = IsoBrandAt) }

private fun ByteArray.startsWith(mark: ByteArray, at: Int = 0): Boolean =
    size >= at + mark.size && mark.indices.all { this[at + it] == mark[it] }

/** A protected PDF starts as any other: it is kept, and the page reader says what it is. */
private fun isPdf(file: File): Boolean = try {
    file.inputStream().use { it.readNBytes(PdfMark.size).contentEquals(PdfMark) }
} catch (_: IOException) {
    false
}
