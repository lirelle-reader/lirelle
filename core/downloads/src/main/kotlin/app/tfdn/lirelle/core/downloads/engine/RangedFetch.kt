package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DownloadFolders
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

private const val VersionSuffix = ".version"
private const val PartialContent = 206
private const val RangeNotSatisfiable = 416
private const val ProgressEveryMillis = 250L

/** `bytes <from>-<to>/<total>`, the total being `*` when the server does not know it. */
private val ContentRange = Regex("""bytes (\d+)-\d+/(\d+|\*)""")

/**
 * A file while it is received: [part], beside its place [target], and beside the part a small file
 * holding the version the part belongs to. A file with the target's name is always a whole file.
 */
internal class PartFile(val target: File, val part: File) {
    private val version = File(part.path + VersionSuffix)

    /**
     * How many bytes of [file] are there to go on from. What cannot be gone on from is removed:
     * a part of another version, one whose version nobody wrote down, one of a file without ranges.
     */
    fun resumableBytes(file: PlannedFile): Long {
        val kept = file.resumable && part.isFile && version.isFile && version.readText() == file.version.orEmpty()
        if (!kept) discard()
        return if (kept) part.length() else 0
    }

    /**
     * An empty part of [version], its directory made by [folders]: when it is not to be made any more, the
     * profile having closed, the part cannot be written, as on a device that refuses it.
     */
    fun begin(version: String?, folders: DownloadFolders) {
        if (!folders.make(target.parentFile)) throw FileNotFoundException("No folder for ${target.name}")
        this.version.writeText(version.orEmpty())
        part.writeBytes(ByteArray(0))
    }

    fun discard() {
        part.delete()
        version.delete()
    }

    /** The part becomes the file, in one step. */
    fun complete() {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        version.delete()
    }
}

/**
 * Where the body of an answer starts in the file, and the file's whole length when the answer says it.
 */
internal data class BodyStart(val from: Long, val totalBytes: Long?)

internal fun rangedRequest(url: String, from: Long): Request {
    val request = Request.Builder().url(url)
    if (from > 0) request.header("Range", "bytes=$from-")
    return request.build()
}

/**
 * What the body of a successful answer to a request from [asked] is: the rest of the file from there
 * (206 with a matching `Content-Range`), or the whole file when the server ignored the range (200).
 * Null when it is a part of the file that starts elsewhere: nothing to append.
 */
internal fun Response.bodyStart(asked: Long): BodyStart? {
    if (code != PartialContent) return BodyStart(0, body.contentLength().takeIf { it >= 0 })
    val range = header("Content-Range")?.let { ContentRange.matchEntire(it.trim()) } ?: return null
    val (from, total) = range.destructured
    return BodyStart(asked, total.toLongOrNull()).takeIf { from.toLongOrNull() == asked }
}

/** The server has nothing from where the part ends: the part is not of the file it has now. */
internal val Response.refusesRange: Boolean get() = code == RangeNotSatisfiable

/**
 * Runs [block], which uses this call on a thread it blocks, and cancels the call as soon as the
 * coroutine is cancelled: a read waiting for the server then fails at once instead of waiting on.
 */
internal suspend fun <T> Call.cancelledWithCaller(block: suspend () -> T): T = coroutineScope {
    // The handler runs on the thread that cancels: no dispatcher has to be free for it.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { cancel() } }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}

/** A failure to write on the device, apart from those of the network that reads throw. */
internal class WriteFailed(cause: IOException) : IOException(cause)

private val NoSpaceMessages = listOf("ENOSPC", "No space left on device")

/** Whether this failure, or one that caused it, is the system saying the device is full. */
internal fun IOException.saysNoSpace(): Boolean = generateSequence<Throwable>(this) { it.cause }
    .any { failure -> NoSpaceMessages.any { failure.message?.contains(it) == true } }

/** Tells the bytes received, the first time and then no more often than every quarter of a second. */
internal class Progress(private val now: () -> Long, private val tell: (Long) -> Unit) {
    private var toldAt: Long? = null

    fun received(bytes: Long) {
        val time = now()
        val last = toldAt
        if (last != null && time - last < ProgressEveryMillis) return
        toldAt = time
        tell(bytes)
    }
}
