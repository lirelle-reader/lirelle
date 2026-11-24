package app.tfdn.lirelle.core.comics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val AskedWaitSeconds = 5L
private const val QuietMillis = 300L
private val Held = 20.seconds

/** A page higher than wide. */
internal const val PortraitWidth = 2
internal const val PortraitHeight = 3

/** A page wider than high, as a double page is. */
internal const val WideWidth = 4
internal const val WideHeight = 2

private fun jpeg(width: Int, height: Int): ByteArray = picture(width, height, Bitmap.CompressFormat.JPEG)

private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray = ByteArrayOutputStream().also {
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(format, 90, it)
}.toByteArray()

/**
 * Reads the size of a picture from a file, as a fetch does once its page is on the device. Under Robolectric the
 * first reading of a picture's file sets the platform's decoder up, and that set-up aborts the whole test
 * process ("Class not found: java/nio/FloatBuffer") when fetches do it on their own threads; done once here,
 * the fetches find it ready.
 */
internal fun readOnceFromAFile(picture: ByteArray) {
    val file = File.createTempFile("picture", ".jpg")
    try {
        file.writeBytes(picture)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0) { "The picture's file was not read" }
    } finally {
        file.delete()
    }
}

/** The bytes of a portrait page, as the server of the tests answers them. */
internal val PortraitPage: ByteArray by lazy { jpeg(PortraitWidth, PortraitHeight) }
private val WidePage: ByteArray by lazy { jpeg(WideWidth, WideHeight) }

/** A portrait page as the server draws a DjVu's. */
internal val WebpPage: ByteArray by lazy { picture(PortraitWidth, PortraitHeight, Bitmap.CompressFormat.WEBP_LOSSY) }

/** What the server of the tests answers for a page that is no picture. */
private val NoPicture = """{"not":"a picture"}""".toByteArray()

/**
 * The profile's server as its source gives the pages of an album of [pageCount] pages: a source of the tests,
 * which speaks no server's routes. It serves comics and DjVus by pages, and a PDF whole. It notes the pages
 * asked for, in the order the requests came, and while [holding] it keeps each answer until the page is
 * [release]d.
 */
internal class ComicsServer : PageSource {
    private val asked = LinkedBlockingQueue<Int>()
    private val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val failing: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val stalls = ConcurrentHashMap<Int, ConcurrentLinkedQueue<Long>>()

    @Volatile var pageCount = 20

    @Volatile var countFails = false

    @Volatile var holding = false

    /** The server is shut down: nothing it is asked reaches it. */
    @Volatile private var down = false

    /** The pages answered with a picture wider than high. */
    @Volatile var wide: Set<Int> = emptySet()

    /** The pages answered with something that is no picture. */
    @Volatile var broken: Set<Int> = emptySet()
    val countAsked = AtomicInteger()

    /**
     * What the source was asked, in the order it came: `/<kind>/<fileId>/pages` for a count, and
     * `/<kind>/<fileId>/pages/<index>` for a page, the kind in lower case.
     */
    val paths = ConcurrentLinkedQueue<String>()

    /** The pages are answered as the server draws a DjVu's: WebP pictures. */
    @Volatile var webp = false

    /**
     * The pictures are made here, and one is read back from a file, by the test's own thread: the platform's
     * graphics must be loaded by it, not by a thread of a fetch.
     */
    fun start() {
        check(PortraitPage.isNotEmpty() && WidePage.isNotEmpty() && WebpPage.isNotEmpty())
        readOnceFromAFile(PortraitPage)
    }

    fun stop() {
        holding = false
        down = true
        gates.values.forEach { it.complete(Unit) }
    }

    /** The next answer for [index] is a failure of the server; the one after is the page. */
    fun failOnce(index: Int) {
        failing += index
    }

    /**
     * The next answers for [index] begin at once and send their picture only after a while: each of [millis]
     * in turn, one per request. The answers after those are whole at once.
     */
    fun stall(index: Int, vararg millis: Long) {
        stalls.computeIfAbsent(index) { ConcurrentLinkedQueue() } += millis.toList()
    }

    fun release(index: Int) {
        gate(index).complete(Unit)
    }

    /** The page of the next request, in the order they came. */
    fun nextAsked(): Int = checkNotNull(asked.poll(AskedWaitSeconds, TimeUnit.SECONDS)) { "No page was asked for" }

    /** The pages of the next [count] requests, for requests that leave together. */
    fun nextAsked(count: Int): Set<Int> = List(count) { nextAsked() }.toSet()

    /** Null when no page is asked for in a moment. */
    fun askedSoon(): Int? = asked.poll(QuietMillis, TimeUnit.MILLISECONDS)

    override fun servesByPages(kind: PagedKind): Boolean = kind != PagedKind.Pdf

    override suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int> {
        if (!servesByPages(kind)) return ApiResult.Failure(ApiError.NotFound)
        // A shut-down server records nothing: the request never reaches it.
        if (down) return ApiResult.Failure(ApiError.ServerUnreachable)
        paths += "/${kind.name.lowercase()}/$fileId/pages"
        countAsked.incrementAndGet()
        return when {
            countFails -> ApiResult.Failure(ApiError.ServerFailed)
            else -> ApiResult.Success(pageCount)
        }
    }

    override suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit> {
        if (!servesByPages(kind)) return ApiResult.Failure(ApiError.NotFound)
        if (down) return ApiResult.Failure(ApiError.ServerUnreachable)
        paths += "/${kind.name.lowercase()}/$fileId/pages/$index"
        asked += index
        if (holding) withTimeoutOrNull(Held) { gate(index).await() }
        return when {
            // Shut down while the answer was held.
            down -> ApiResult.Failure(ApiError.ServerUnreachable)
            failing.remove(index) -> ApiResult.Failure(ApiError.ServerFailed)
            index >= pageCount -> ApiResult.Failure(ApiError.NotFound)
            index in broken -> send(NoPicture, 0, sink)
            webp -> send(WebpPage, 0, sink)
            else -> send(if (index in wide) WidePage else PortraitPage, stalls[index]?.poll() ?: 0, sink)
        }
    }

    /** No page has an address: the reader fetches its pages through [page]. */
    override fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String? = null

    /** The answer begins at once, and its bytes come after [stalledMillis]. */
    private suspend fun send(bytes: ByteArray, stalledMillis: Long, sink: PageSink): ApiResult<Unit> = try {
        sink.open().use { out ->
            delay(stalledMillis)
            out.write(bytes)
        }
        ApiResult.Success(Unit)
    } catch (_: IOException) {
        ApiResult.Failure(ApiError.Unexpected)
    }

    private fun gate(index: Int): CompletableDeferred<Unit> = gates.computeIfAbsent(index) { CompletableDeferred() }
}
