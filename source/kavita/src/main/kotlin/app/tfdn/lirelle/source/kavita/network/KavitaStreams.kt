package app.tfdn.lirelle.source.kavita.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// The bytes Kavita sends (a page, a whole file), copied to the device a block at a time.

private const val BlockBytes = 64 * 1024

/** The device could not take the bytes (no room left, a cache cleared under the fetch): not the server's doing. */
internal class SinkFailure(cause: IOException) : RuntimeException(cause)

/** A failure of [action] is the device's, and is told apart from the network's. */
internal inline fun <T> onDevice(action: () -> T): T = try {
    action()
} catch (failure: IOException) {
    throw SinkFailure(failure)
}

/**
 * [source] into [sink], a block at a time: nothing is held whole in memory, and a cancelled fetch stops between two
 * blocks. A read ended by the fetch being given up is not a failure of the server; a write that fails is the
 * device's ([SinkFailure]). The sink is neither flushed nor closed.
 */
internal suspend fun copyBlocks(source: InputStream, sink: OutputStream) {
    val block = ByteArray(BlockBytes)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = try {
            source.read(block)
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
        if (read < 0) break
        onDevice { sink.write(block, 0, read) }
    }
}

/**
 * [source] into [sink] as [copyBlocks] does, then the sink is closed: a close that fails is the device's too
 * ([SinkFailure]), but never hides the failure of the copy, which is the one told.
 */
internal suspend fun copyBlocksAndClose(source: InputStream, sink: OutputStream) {
    try {
        copyBlocks(source, sink)
    } catch (copy: Throwable) {
        try {
            sink.close()
        } catch (_: IOException) {
            // The copy's failure is the one told.
        }
        throw copy
    }
    onDevice { sink.close() }
}
