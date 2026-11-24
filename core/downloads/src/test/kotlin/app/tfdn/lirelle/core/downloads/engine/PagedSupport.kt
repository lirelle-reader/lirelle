package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.comics.ServedPages
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.StoredBook
import app.tfdn.lirelle.core.downloads.store.stored
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer

/** The file the test albums are the pages of. */
internal val AlbumFile = FileId("7")

/** The size the server says of the test album's file: the estimate of what its pages take. */
internal const val AlbumBytes = 6_000L

private const val JpegMarkSize = 4
private const val Overlap = 5L

/** The page reader's side, as a test says it. */
internal class FakeServedPages : ServedPages {
    var count: ApiResult<Int> = ApiResult.Success(0)

    /** The kind and the file of each count asked, in order. */
    val asked = mutableListOf<Pair<PagedKind, FileId>>()

    /** The pages the reader has seen, by file, version and index. */
    val seen = mutableMapOf<Triple<FileId, String?, Int>, File>()

    /** As the source of the tests: comics and DjVus by pages, a PDF whole. */
    override fun servesByPages(kind: PagedKind): Boolean = kind != PagedKind.Pdf

    override suspend fun count(kind: PagedKind, fileId: FileId): ApiResult<Int> = count.also { asked += kind to fileId }

    override fun cached(fileId: FileId, contentVersion: String?, index: Int): File? =
        seen[Triple(fileId, contentVersion, index)]?.takeIf { it.isFile }
}

/** [size] bytes that start as a JPEG does; [seed] tells one picture from another. */
internal fun jpeg(size: Int, seed: Int = 0): ByteArray {
    val mark = listOf(0xFF, 0xD8, 0xFF, 0xE0).map { it.toByte() }
    return ByteArray(size) { if (it < JpegMarkSize) mark[it] else ((it + seed) % 251).toByte() }
}

/** The plan of a comic of [count] pages; where they are served is the locations' business. */
internal fun album(
    count: Int,
    version: String = "v1",
    fileId: FileId = AlbumFile,
    name: TextName = TextName.Cbz,
) = FormatPlan(
    fileId = fileId,
    version = version,
    files = pageFiles(fileId, PagedKind.Comic, count, version),
    textName = name,
    expectedBytes = AlbumBytes,
)

/** The text of [bookId] is a comic of [count] pages, fetched by no server: the fixture's fetcher. */
internal fun QueueFixture.serveAlbum(bookId: BookId, count: Int, version: String = "v1", fileId: FileId = AlbumFile) {
    plans.answers[bookId to DownloadFormat.Text] = ApiResult.Success(album(count, version, fileId))
}

internal suspend fun QueueFixture.text(bookId: BookId): FormatRecord =
    checkNotNull(registry.book(bookId)).formats.first { it.format == DownloadFormat.Text }

/** Every field written, defaults included, and no null: so that [written] shows each field the record has. */
private val EveryField = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** What the register would write of [bookId]. */
internal suspend fun QueueFixture.written(bookId: BookId): String =
    EveryField.encodeToString(StoredBook.serializer(), checkNotNull(registry.book(bookId)).stored())

/** The names of the pages kept of [bookId], in order. */
internal fun QueueFixture.keptPages(bookId: BookId): List<String> =
    File(files.bookDir(bookId), "pages").list().orEmpty().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

/**
 * The page route of a server: each page is what [page] says of its index, sent without a length when it is a
 * picture's as the server does. It notes the paths asked, in order, and how many requests it was answering
 * at once at most.
 *
 * @param onAsked told the index of each page as it is asked, before it is answered.
 */
internal class ServedAlbum(
    private val page: (Int) -> ByteArray,
    private val onAsked: (Int) -> Unit = {},
) : Dispatcher() {
    val asked = CopyOnWriteArrayList<String>()

    /** The pages whose next answer loses its connection on the way. */
    val cuts: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val going = AtomicInteger()
    private val most = AtomicInteger()

    /** How many requests were being answered together, at most. */
    val atOnce: Int get() = most.get()

    override fun dispatch(request: RecordedRequest): MockResponse {
        most.accumulateAndGet(going.incrementAndGet(), ::maxOf)
        try {
            asked += request.url.encodedPath
            val index = request.url.pathSegments.last().toInt()
            onAsked(index)
            // Long enough for a second request, if one were made meanwhile, to be seen.
            Thread.sleep(Overlap)
            val answer = MockResponse.Builder().addHeader("Content-Type", "image/jpeg")
                .chunkedBody(Buffer().write(page(index)), maxChunkSize = 1024)
            if (cuts.remove(index)) answer.onResponseBody(SocketEffect.ShutdownConnection)
            return answer.build()
        } finally {
            going.decrementAndGet()
        }
    }
}
