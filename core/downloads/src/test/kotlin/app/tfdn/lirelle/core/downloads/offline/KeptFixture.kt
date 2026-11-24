package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.narration.FileReadAlongMapStore
import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import java.io.File
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import okhttp3.OkHttpClient
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/** A place for the downloads, the device's read-along maps, and a server for the covers. */
internal class KeptFixture : ExternalResource() {
    private val folder = TemporaryFolder()
    private var time = 0L
    val server = MockWebServer()
    val readAlong = FakeReadAlongRepository().apply {
        playlistResult = ApiResult.Success(Recorded.playlist("after"))
        spineResult = ApiResult.Success(Recorded.spine("after"))
    }
    lateinit var files: DownloadFiles

    /** The device's own maps: as many as [mapsKept], the least recently used out. */
    var mapsKept = 10
    val maps by lazy {
        FileReadAlongMapStore(File(folder.root, "readalong"), readAlong, now = { time += 1_000; time }, kept = mapsKept)
    }

    /** The device's own maps go, as the least recently used do: what was kept beside a download is not there. */
    fun dropOwnMaps() {
        File(folder.root, "readalong").deleteRecursively()
    }

    override fun before() {
        folder.create()
        server.start()
        // A cover the test gave no answer for is refused at once, rather than waited for.
        (server.dispatcher as QueueDispatcher).setFailFast(true)
        files = DownloadFiles(File(folder.root, "downloads"))
    }

    override fun after() {
        server.close()
        folder.delete()
    }

    /** What the device keeps whole of each book: nothing until a test records it. */
    val registry = MemoryRegistry()

    /** A new instance each time, as after the app was closed. */
    fun kept(): KeptBookData = FileKeptBookData(files, OkHttpClient(), maps, registry)

    fun coverUrl(bookId: BookId): String = server.url("/api/v1/books/$bookId/cover").toString()

    /** The audiobook's own cover of the book, and its thumbnail. */
    fun audioCover(bookId: BookId) = MediumCover(
        url = server.url("/api/v1/books/$bookId/cover?medium=audio").toString(),
        thumbnailUrl = server.url("/api/v1/books/$bookId/thumbnail?medium=audio").toString(),
    )
}
