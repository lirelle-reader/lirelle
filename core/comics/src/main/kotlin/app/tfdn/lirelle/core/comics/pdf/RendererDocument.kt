package app.tfdn.lirelle.core.comics.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.tfdn.lirelle.core.comics.fetch.ImageSize
import java.io.File

/**
 * The PDF in [file], drawn by the system's renderer. Opening it fails as [PdfDocuments.open] says: the
 * renderer throws a [SecurityException] for a protected document and an [java.io.IOException] for a file it
 * cannot read.
 */
internal class RendererDocument(file: File) : PdfDocument {
    private val renderer: PdfRenderer = open(file)

    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(index: Int): ImageSize = renderer.openPage(index).use { ImageSize(it.width, it.height) }

    /** On white: a PDF's page has no background of its own, and the renderer leaves untouched what it does not draw. */
    override fun render(index: Int, size: ImageSize): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        var drawn = false
        try {
            bitmap.eraseColor(Color.WHITE)
            // Without a clip nor a transform the page is fitted to the whole picture.
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            drawn = true
            return bitmap
        } finally {
            if (!drawn) bitmap.recycle()
        }
    }

    /** The renderer closes the file with itself. */
    override fun close() = renderer.close()
}

/** A renderer that could not be made leaves no file open. */
private fun open(file: File): PdfRenderer {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    var opened = false
    try {
        return PdfRenderer(descriptor).also { opened = true }
    } finally {
        if (!opened) descriptor.close()
    }
}
