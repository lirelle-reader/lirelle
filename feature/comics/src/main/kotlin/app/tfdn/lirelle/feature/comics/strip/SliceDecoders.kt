package app.tfdn.lirelle.feature.comics.strip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many pictures are kept open at once: the pages on screen and their neighbours. */
private const val OpenAtOnce = 4

/**
 * Decodes slices of the pictures of pages too tall to be decoded whole. A picture stays open while its slices
 * are asked for, a few at once, the one asked longest ago closed first. A picture is told from another by its
 * file and the time that file was written: a page the downloads keep has the same file after an update of its
 * album.
 *
 * Slices are asked from several threads. The pictures open are found under one lock, and one that is not open
 * is opened under it, which reads the head of its file: the slices of a page asked together open its picture
 * once. Each picture is read and closed under a lock of its own, so that it is never closed under a region being
 * decoded, which may be long. The two are never held together.
 *
 * Once [close]d it decodes nothing more: a slice still on its way then gives null and opens no picture, so that
 * none is left open behind the strip.
 */
internal class SliceDecoders(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private data class Key(val path: String, val written: Long)

    private val open = LinkedHashMap<Key, BitmapRegionDecoder>(OpenAtOnce, 1f, true)

    /** Whether the strip is gone. Written without a lock, which [close] never waits for. */
    @Volatile
    private var closed = false

    /** How many pictures are open. */
    val openCount: Int get() = synchronized(open) { open.size }

    /**
     * The rows [slice] of the picture in [file], one pixel in [sample] kept each way; null when the picture cannot
     * be read in parts (a format the device does not cut, a file that is gone or is no picture, rows the device
     * has no memory for) or has no such rows, and once the decoders are [close]d.
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
                // Closed since it was found, for another picture to open: it is opened again. With the strip: it
                // is not, and the slice is given up.
                if (!decoder.isRecycled) return decoder.region(slice, options)
            }
        }
    }

    /**
     * Closes every picture, for good: the strip is gone. A slice asked after it, or being asked, has no picture.
     *
     * It does not wait: it is called from the main thread, and a picture is closed only once the region being
     * decoded from it is, or once the one being opened is. The pictures are closed where they are decoded, in a
     * scope of their own, which the strip that is gone does not end; it holds nothing once they are.
     */
    fun close() {
        closed = true
        CoroutineScope(dispatcher).launch {
            // A picture opened while this was called is in the map by now: none is opened after.
            val left = synchronized(open) { open.values.toList().also { open.clear() } }
            left.forEach { it.close() }
        }
    }

    /**
     * The picture of [file], opened if it is not; null when it cannot be, and once the decoders are closed. Those
     * it takes the place of are closed.
     */
    private fun decoder(file: File): BitmapRegionDecoder? {
        val key = Key(file.path, file.lastModified())
        val replaced = mutableListOf<BitmapRegionDecoder>()
        val found = synchronized(open) {
            if (closed) return null
            open[key] ?: open(file)?.also { opened ->
                // The picture the file held before it was written again will not be asked for.
                replaced += open.keys.filter { it.path == key.path }.mapNotNull(open::remove)
                open[key] = opened
                while (open.size > OpenAtOnce) replaced += checkNotNull(open.remove(open.keys.first()))
            }
        }
        // Outside the lock of the pictures open: a region being decoded from one ends first, and may be long.
        replaced.forEach { it.close() }
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
    } catch (_: OutOfMemoryError) {
        // Too large for what memory is left: the page is drawn whole, smaller, instead of ending the reader.
        null
    }
}
