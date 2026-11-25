package app.tfdn.lirelle.feature.comics.strip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** How many pictures are kept open at once: the pages on screen and their neighbours. */
private const val OpenAtOnce = 4

/**
 * Decodes slices of the pictures of pages too tall to be decoded whole. A picture stays open while its slices
 * are asked for, a few at once, the one asked longest ago closed first. A picture is told from another by its
 * file and the time that file was written: a page the downloads keep has the same file after an update of its
 * album.
 *
 * Slices are asked from several threads. The pictures open are found under one lock, held only for that; each
 * picture is read and closed under its own, so that it is never closed under a region being decoded. The two are
 * never held together.
 */
internal class SliceDecoders(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private data class Key(val path: String, val written: Long)

    private val open = LinkedHashMap<Key, BitmapRegionDecoder>(OpenAtOnce, 1f, true)

    /** How many pictures are open. */
    val openCount: Int get() = synchronized(open) { open.size }

    /**
     * The rows [slice] of the picture in [file], one pixel in [sample] kept each way; null when the picture cannot
     * be read in parts (a format the device does not cut, a file that is gone or is no picture) or has no such rows.
     */
    suspend fun decode(file: File, slice: StripSlice, sample: Int): Bitmap? = withContext(dispatcher) {
        ensureActive()
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        // A slice nobody waits for any more is not kept until the memory is collected.
        region(file, slice, options).also { if (!isActive) it?.recycle() }
    }

    private fun region(file: File, slice: StripSlice, options: BitmapFactory.Options): Bitmap? {
        while (true) {
            val decoder = decoder(file) ?: return null
            // One region at a time for a picture: its decoder is not made for more.
            synchronized(decoder) {
                // Closed since it was found, for another picture to open or with the strip: it is opened again.
                if (!decoder.isRecycled) return decoder.region(slice, options)
            }
        }
    }

    /** Closes every picture: the strip is gone. A slice asked after it opens its picture again. */
    fun close() {
        val closed = synchronized(open) { open.values.toList().also { open.clear() } }
        closed.forEach { it.close() }
    }

    /** The picture of [file], opened if it is not; null when it cannot be. Those it takes the place of are closed. */
    private fun decoder(file: File): BitmapRegionDecoder? {
        val key = Key(file.path, file.lastModified())
        val closed = mutableListOf<BitmapRegionDecoder>()
        val found = synchronized(open) {
            open[key] ?: open(file)?.also { opened ->
                // The picture the file held before it was written again will not be asked for.
                closed += open.keys.filter { it.path == key.path }.mapNotNull(open::remove)
                open[key] = opened
                while (open.size > OpenAtOnce) closed += checkNotNull(open.remove(open.keys.first()))
            }
        }
        // Outside the lock of the pictures open: a region being decoded from one ends first, and may be long.
        closed.forEach { it.close() }
        return found
    }
}

private fun open(file: File): BitmapRegionDecoder? = try {
    BitmapRegionDecoder.newInstance(file.path)
} catch (_: IOException) {
    null
}

/** Closes the picture once the region being decoded from it, if any, is. */
private fun BitmapRegionDecoder.close() = synchronized(this) { recycle() }

/** The rows [slice] of the picture, cut at its end; null when it has none of them or they cannot be decoded. */
private fun BitmapRegionDecoder.region(slice: StripSlice, options: BitmapFactory.Options): Bitmap? {
    val rows = Rect(0, slice.top.coerceAtLeast(0), width, slice.bottom.coerceAtMost(height))
    if (rows.isEmpty) return null
    return try {
        decodeRegion(rows, options)
    } catch (_: IllegalArgumentException) {
        null
    }
}
