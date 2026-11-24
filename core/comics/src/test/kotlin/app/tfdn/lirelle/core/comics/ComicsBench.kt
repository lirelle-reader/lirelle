package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.comics.kept.KeptCopies
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.library.offline.KeptPages
import app.tfdn.lirelle.core.testing.FakeComicPages
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val Patience = 10.seconds

/** The file every test reads, unless it says another. */
internal val AlbumFileId = FileId("7")

/** The book that file is of. */
internal val AlbumBookId = BookId("3")

/** The pages of albums as the app builds them, from a source of the test's and a directory of its own. */
internal class ComicsBench(limitBytes: Long = Long.MAX_VALUE) {
    val web = ComicsServer().also { it.start() }
    val directory: File = Files.createTempDirectory("comics").toFile()

    /** The time the store reads when an album is opened. */
    var time = 10_000L
    val store = ComicStore(directory, limitBytes, now = { time })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The keys handed to the opener of PDF documents, which is not the one under test here. */
    val pdfAsked = mutableListOf<ComicKey>()

    /** The pages the downloads keep of the book's file, when the test has some: none unless it says so. */
    @Volatile var kept: KeptPages? = null

    /** How many times the book is held as in use, less those given back. */
    val bookHeld = AtomicInteger()

    /** How many times the downloads were asked for the pages they keep. */
    val keptAsked = AtomicInteger()
    private val downloaded = object : DownloadedFiles {
        override suspend fun text(bookId: BookId, fileId: FileId): File? = null

        override suspend fun pages(bookId: BookId, fileId: FileId): KeptPages? {
            keptAsked.incrementAndGet()
            return kept.takeIf { bookId == AlbumBookId && fileId == AlbumFileId }
        }

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
    val opener = DefaultComicPagesOpener(
        store = store,
        source = web,
        pdf = { key, _ -> PdfAsked.also { pdfAsked += key } },
        copies = KeptCopies(downloaded, holds),
        io = Dispatchers.IO,
    )

    fun open(
        fileId: FileId = AlbumFileId,
        version: String? = "v1",
        kind: PagedKind = PagedKind.Comic,
    ): ComicPages =
        opener.open(ComicKey(fileId, version, kind, AlbumBookId), scope)

    /** The names of what the store keeps, album by album. */
    fun albums(): List<String> = directory.list().orEmpty().sorted()

    /** The names of what the album [name] keeps. */
    fun kept(name: String): List<String> = File(directory, name).list().orEmpty().sorted()

    /**
     * Everything the albums were doing has ended before the server lets its held answers go and the folder is
     * deleted: no fetch reads a picture, on a thread of its own, after the test that started it.
     */
    fun close() {
        runBlocking { withTimeout(Patience) { scope.coroutineContext.job.cancelAndJoin() } }
        web.stop()
        directory.deleteRecursively()
    }
}

/** What the bench gives for a PDF: its pages are another bench's. */
internal val PdfAsked: ComicPages = FakeComicPages()

/** The page once it is on the device; fails when it does not come. */
internal suspend fun ComicPages.ready(index: Int): PageState.Ready =
    withTimeout(Patience) { page(index).first { it is PageState.Ready } as PageState.Ready }

/** The page's failure; fails when there is none. */
internal suspend fun ComicPages.failed(index: Int): PageState.Failed =
    withTimeout(Patience) { page(index).first { it is PageState.Failed } as PageState.Failed }
