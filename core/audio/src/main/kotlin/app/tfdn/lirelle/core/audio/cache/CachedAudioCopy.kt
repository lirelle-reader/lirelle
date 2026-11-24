package app.tfdn.lirelle.core.audio.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val PartSuffix = ".part"
private const val BufferBytes = 64 * 1024

/**
 * Copies to [into] the file the cache holds under [key], when it holds all of it: its length is known, is
 * [expectedBytes] when that is given, and every byte is there. False otherwise, and when the copy cannot be
 * written: nothing is left at [into] then. The cache keeps its own, and nothing is asked of the server.
 *
 * It reads and writes files: call it off the main thread.
 */
@UnstableApi
fun AudioCache.copyWhole(key: String, expectedBytes: Long?, into: File): Boolean {
    // Whole or not at all: written beside its place, then moved onto it.
    val part = File(into.path + PartSuffix)
    return try {
        val length = wholeLength(key)
        if (length == null || (expectedBytes != null && expectedBytes != length)) return false
        val written = part.outputStream().use { out -> writeCached(key, length, out) }
        if (written != length) return false
        Files.move(part.toPath(), into.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: IOException) {
        // The cache dropped a part of it meanwhile, or there is no room: the file is fetched.
        false
    } catch (_: IllegalStateException) {
        // The cache was closed meanwhile (its profile closed).
        false
    } finally {
        part.delete()
    }
}

/** The length of the content under [key] when the cache knows it and has every byte of it. */
@UnstableApi
private fun AudioCache.wholeLength(key: String): Long? {
    val cache = cache
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    return length.takeIf { it > 0 && cache.isCached(key, 0, it) }
}

/** Reads from the cache alone: a byte it has not fails, rather than being asked to the server. */
@UnstableApi
private fun AudioCache.writeCached(key: String, length: Long, out: OutputStream): Long {
    val source = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(AudioCache.keyFactory)
        .setUpstreamDataSourceFactory(null)
        .createDataSource()
    val buffer = ByteArray(BufferBytes)
    var written = 0L
    try {
        source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setLength(length).build())
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) break
            out.write(buffer, 0, read)
            written += read
        }
    } finally {
        source.close()
    }
    return written
}
