package app.tfdn.lirelle.core.comics.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import java.io.IOException

private const val AlphaShift = 24
private const val RedShift = 16
private const val GreenShift = 8
private const val Full = 0xFF
private const val Half = Full / 2

/**
 * The picture in [file] as the cut reads it: at the working size on its long side, or as it is when smaller, and
 * over white where it lets through. Decoded for the cut alone, apart from what the page view shows. Tells a file
 * that will never give a picture to cut from one that gave none this time (see [PageDecoding]). Not for the main
 * thread.
 */
internal fun decodeForCut(file: File): PageDecoding {
    if (!file.isFile) return PageDecoding.NotNow
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return unreadable(file)
    if (!cutsWithOrientation(orientationOf(file))) return PageDecoding.NoPicture
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    // Halved while what is left is still at least the working size: the scaling after that is the smaller part.
    var sample = 1
    while (longSide / (sample * 2) >= WorkingSide) sample *= 2
    return try {
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        // Its size was read, so it is a picture: none now is a want of room, or a file cut short while it is
        // written, which the system tells by giving nothing. Tried again another time, not kept as never cut.
        if (decoded == null) PageDecoding.NotNow else PageDecoding.Picture(pixelsOf(decoded))
    } catch (_: OutOfMemoryError) {
        PageDecoding.NotNow
    }
}

/**
 * Whether a picture whose file says the [orientation] it is to be shown in (one of the `ORIENTATION_` values of
 * [ExifInterface]) is cut. The page view turns the picture as its file says, the decoding here does not: zones
 * found on a picture that is turned or mirrored would lie elsewhere on the page shown, so only a picture shown as
 * it is stored is cut.
 */
internal fun cutsWithOrientation(orientation: Int): Boolean =
    orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED

/** The orientation the [file] says its picture is to be shown in; undefined when it says none or cannot be read. */
private fun orientationOf(file: File): Int = try {
    ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
} catch (_: IOException) {
    ExifInterface.ORIENTATION_UNDEFINED
}

/** What a [file] that gave no picture is: no picture when it is there, to be read another time when it is gone. */
private fun unreadable(file: File): PageDecoding = if (file.isFile) PageDecoding.NoPicture else PageDecoding.NotNow

/** The pixels of the [decoded] picture at the working size; the picture is given back to the system. */
private fun pixelsOf(decoded: Bitmap): PagePixels {
    val scaled = try {
        toWorkingSize(decoded)
    } catch (full: OutOfMemoryError) {
        decoded.recycle()
        throw full
    }
    try {
        val argb = IntArray(scaled.width * scaled.height)
        scaled.getPixels(argb, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled.hasAlpha()) overWhite(argb)
        return PagePixels(scaled.width, scaled.height, argb)
    } finally {
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }
}

private fun toWorkingSize(bitmap: Bitmap): Bitmap {
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide <= WorkingSide) return bitmap
    val width = Math.round(bitmap.width.toDouble() * WorkingSide / longSide).toInt().coerceAtLeast(1)
    val height = Math.round(bitmap.height.toDouble() * WorkingSide / longSide).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

/**
 * Puts the pixels of [argb] over white, the paper of a page: a pixel that lets everything through reads black
 * otherwise, and a page with such a margin would have black for its gutters. Done on the pixels read, so the
 * device holds no second picture for it.
 */
private fun overWhite(argb: IntArray) {
    for (at in argb.indices) {
        val pixel = argb[at]
        val alpha = pixel ushr AlphaShift
        if (alpha != Full) {
            val red = overWhite(red(pixel), alpha)
            val green = overWhite(green(pixel), alpha)
            val blue = overWhite(blue(pixel), alpha)
            argb[at] = Full shl AlphaShift or (red shl RedShift) or (green shl GreenShift) or blue
        }
    }
}

/** A [channel] of a pixel as opaque as [alpha] says, both from 0 to 255, once over white. */
private fun overWhite(channel: Int, alpha: Int): Int = (channel * alpha + Full * (Full - alpha) + Half) / Full
