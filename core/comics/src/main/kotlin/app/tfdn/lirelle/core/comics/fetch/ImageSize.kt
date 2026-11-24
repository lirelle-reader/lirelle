package app.tfdn.lirelle.core.comics.fetch

import android.graphics.BitmapFactory
import java.io.File

internal data class ImageSize(val width: Int, val height: Int)

/** The size of the picture in [file], read from its header: the picture is not decoded. Null when it is none. */
internal fun imageSize(file: File): ImageSize? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return ImageSize(options.outWidth, options.outHeight).takeIf { it.width > 0 && it.height > 0 }
}
