package app.tfdn.lirelle.core.comics.pdf

import android.graphics.Bitmap
import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.PortraitPage
import app.tfdn.lirelle.core.comics.fetch.ImageSize
import app.tfdn.lirelle.core.comics.readOnceFromAFile
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.DefaultReaderCache
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val Patience = 10.seconds
private const val LookMillis = 10L
private const val DrawnWaitSeconds = 5L
private const val QuietMillis = 300L
private const val HeldSeconds = 20L

/** The book and the file every test reads, unless it says another. */
internal val PdfBookId = BookId("3")
internal val PdfFileId = FileId("7")

/** A test file of `formats/`, as the server would send it. */
internal fun fixture(name: String): ByteArray =
    checkNotNull(PdfBench::class.java.getResourceAsStream("/formats/$name")) { "No test file $name" }.readBytes()

/** One drawing the document was asked for: the page and the size in pixels. */
internal data class Drawn(val index: Int, val width: Int, val height: Int)

/**
 * Stands for the system's renderer, which Robolectric does not have. It reads of a real PDF what the tests
 * need: a file cut short is damaged, one with an `/Encrypt` entry is protected, and the pages have the sizes
 * of the `/MediaBox` entries, unless [sizes] says others. It notes what was drawn and how many drawings ran
 * at once, and while [holding] it keeps each drawing until the page is [release]d.
 */
internal class FakePdfDocuments : PdfDocuments {
    /** The pages' sizes in points, for a document of more pages than the test files have. */
    @Volatile var sizes: List<ImageSize>? = null

    @Volatile var holding = false

    /** The pages whose drawing fails. */
    @Volatile var failing: Set<Int> = emptySet()

    /** What a drawing that fails throws. */
    @Volatile var failure: () -> Throwable = { IllegalStateException("The page could not be opened") }

    /** What opening a document throws, when it is to throw what the renderer does not say it throws. */
    @Volatile var refusal: (() -> Throwable)? = null
    val opened = CopyOnWriteArrayList<File>()
    val closed = AtomicInteger()
    val drawn = CopyOnWriteArrayList<Drawn>()
    val mostAtOnce = AtomicInteger()
    private val atOnce = AtomicInteger()
    private val asked = LinkedBlockingQueue<Int>()
    private val gates = ConcurrentHashMap<Int, CountDownLatch>()

    override fun open(file: File): PdfDocument {
        refusal?.let { throw it() }
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        if ("%%EOF" !in text) throw IOException("file not in PDF format or corrupted")
        if ("/Encrypt" in text) throw SecurityException("password required or incorrect password")
        opened += file
        val boxes = Regex("""/MediaBox \[ 0 0 (\d+)\.0 (\d+)\.0 ]""").findAll(text)
            .map { ImageSize(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
        return Document(sizes ?: boxes)
    }

    fun release(index: Int) = gate(index).countDown()

    fun releaseAll() {
        holding = false
        gates.values.forEach { it.countDown() }
    }

    /** The page of the next drawing, in the order they began. */
    fun nextDrawn(): Int = checkNotNull(asked.poll(DrawnWaitSeconds, TimeUnit.SECONDS)) { "No page was drawn" }

    /** Null when no drawing begins in a moment. */
    fun drawnSoon(): Int? = asked.poll(QuietMillis, TimeUnit.MILLISECONDS)

    private fun gate(index: Int): CountDownLatch = gates.computeIfAbsent(index) { CountDownLatch(1) }

    private inner class Document(private val pages: List<ImageSize>) : PdfDocument {
        override val pageCount: Int get() = pages.size

        override fun pageSize(index: Int): ImageSize = pages[index]

        override fun render(index: Int, size: ImageSize): Bitmap {
            mostAtOnce.accumulateAndGet(atOnce.incrementAndGet(), ::maxOf)
            try {
                asked += index
                drawn += Drawn(index, size.width, size.height)
                if (holding) gate(index).await(HeldSeconds, TimeUnit.SECONDS)
                if (index in failing) throw failure()
                return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            } finally {
                atOnce.decrementAndGet()
            }
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }
}

/** The cache of whole files, with what was held and given back counted. */
internal class WatchedCache(private val cache: ReaderCache) : ReaderCache by cache {
    val held = AtomicInteger()
    val discarded = CopyOnWriteArrayList<CachedBookKey>()

    override fun hold(key: CachedBookKey) {
        held.incrementAndGet()
        cache.hold(key)
    }

    override fun release(key: CachedBookKey) {
        held.decrementAndGet()
        cache.release(key)
    }

    override suspend fun discard(key: CachedBookKey) {
        discarded += key
        cache.discard(key)
    }
}

/**
 * The pages of PDF documents as the app builds them: the page store and the cache of whole files in
 * directories of the test's, a server that sends [FakeReaderRepository.fileContent], and [documents] for the
 * system's renderer.
 */
internal class PdfBench(limitBytes: Long = Long.MAX_VALUE) {
    val directory: File = Files.createTempDirectory("pdf-pages").toFile()
    private val cacheDirectory: File = Files.createTempDirectory("pdf-cache").toFile()
    val server = FakeReaderRepository().apply { fileContent = fixture("three-pages.pdf") }

    /** The copy the downloads keep of the book's file, when the test has one. */
    @Volatile var kept: File? = null

    /** How many times the book is held as in use, less those given back. */
    val bookHeld = AtomicInteger()
    private val downloaded = object : DownloadedFiles {
        override suspend fun text(bookId: BookId, fileId: FileId): File? = kept.takeIf { bookId == PdfBookId }

        override fun audio(url: String): File? = null
    }
    private val holds = object : BookHolds {
        override fun hold(bookId: BookId) {
            bookHeld.incrementAndGet()
        }

        override fun release(bookId: BookId) {
            bookHeld.decrementAndGet()
        }
    }
    private val shelf = DefaultReaderCache(
        repository = server,
        directory = cacheDirectory,
        limitBytes = Long.MAX_VALUE,
        io = Dispatchers.IO,
        downloaded = downloaded,
        books = holds,
    )
    val cache = WatchedCache(shelf)
    val documents = FakePdfDocuments()
    var time = 10_000L
    val store = ComicStore(directory, limitBytes, now = { time })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opener = PdfPagesOpener(PdfParts(store, cache, documents, Dispatchers.IO))

    init {
        // The platform's graphics are loaded by the test's own thread: a picture is made and read back here.
        readOnceFromAFile(PortraitPage)
    }

    fun open(fileId: FileId = PdfFileId, version: String? = "v1", bookId: BookId? = PdfBookId): ComicPages =
        opener.open(ComicKey(fileId, version, PagedKind.Pdf, bookId), scope)

    fun albums(): List<String> = directory.list().orEmpty().sorted()

    fun kept(name: String): List<String> = File(directory, name).list().orEmpty().sorted()

    /** The drawings held are let go, and everything the documents were doing has ended, before the test does. */
    fun close() {
        documents.releaseAll()
        server.downloadGate?.complete(Unit)
        runBlocking { withTimeout(Patience) { scope.coroutineContext.job.cancelAndJoin() } }
        directory.deleteRecursively()
        cacheDirectory.deleteRecursively()
    }
}

/** The page once it is drawn with [longSide] pixels on its long side; fails when it does not come. */
internal suspend fun ComicPages.drawnAt(index: Int, longSide: Int): PageState.Ready = withTimeout(Patience) {
    page(index).first { it is PageState.Ready && maxOf(it.width, it.height) == longSide } as PageState.Ready
}

/** Waits until [condition] holds; fails when it never does. */
internal suspend fun awaitThat(condition: () -> Boolean) = withTimeout(Patience) {
    while (!condition()) delay(LookMillis)
}
