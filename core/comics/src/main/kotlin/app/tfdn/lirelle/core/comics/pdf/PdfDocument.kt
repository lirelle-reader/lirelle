package app.tfdn.lirelle.core.comics.pdf

import android.graphics.Bitmap
import app.tfdn.lirelle.core.comics.fetch.ImageSize
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A PDF open on the device, as the pages need it. It is asked one thing at a time, by whoever draws: the
 * system's renderer serves one thread and has one page open at a time.
 */
internal interface PdfDocument : Closeable {
    val pageCount: Int

    /** The size of the page [index] in points, a point being 1/72 inch. */
    fun pageSize(index: Int): ImageSize

    /**
     * The page [index] drawn whole on a white picture of [size] pixels, which is the caller's to recycle.
     *
     * @throws IOException when the page cannot be read.
     * @throws IllegalStateException when the renderer refuses the page.
     */
    fun render(index: Int, size: ImageSize): Bitmap
}

/** Opens the PDF documents. */
internal fun interface PdfDocuments {
    /**
     * @throws SecurityException when the document asks for a password.
     * @throws IOException when the file is not a PDF, or not a whole one.
     */
    fun open(file: File): PdfDocument
}
