package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.engine.DownloaderFixture
import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.engine.TheBook
import app.tfdn.lirelle.core.downloads.engine.bytes
import app.tfdn.lirelle.core.downloads.engine.whole
import app.tfdn.lirelle.core.downloads.offline.KeptFixture
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadFolders
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A download, or the page of a book kept beside it, may end a moment after its profile closed; at a sign-out
 * the profile's folder is deleted next. What is written then must not bring back the folder of its downloads.
 */
class ClosedProfileDownloadsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val fetching = DownloaderFixture()

    @get:Rule
    internal val keeping = KeptFixture()

    /** The folder of the downloads: the parent of every book's. */
    private val DownloadFiles.root: File get() = checkNotNull(bookDir(TheBook).parentFile)

    @Test
    fun `a folder is made until the profile closes, and none after`() {
        val folders = DownloadFolders()
        val before = File(folder.root, "downloads/3/text")
        val after = File(folder.root, "downloads/4/text")

        assertThat(folders.make(before)).isTrue()
        assertThat(folders.make(before)).isTrue()
        folders.close()

        assertThat(folders.make(after)).isFalse()
        assertThat(after.exists()).isFalse()
        // Not even one that is there: nothing is written for a profile that closed.
        assertThat(folders.make(before)).isFalse()
    }

    @Test
    fun `the files of an update are closed with the kept ones`() {
        val files = DownloadFiles(File(folder.root, "downloads"))

        files.folders.close()

        assertThat(files.updates.folders.make(File(folder.root, "downloads/3/update/text"))).isFalse()
    }

    @Test
    fun `a file fetched once the profile closed is not written, and the folder is not made again`() = runBlocking {
        val file = fetching.audio()
        fetching.server.enqueue(whole(bytes(100)).build())
        fetching.files.folders.close()

        val result = fetching.fetch(file)

        assertThat(result).isNotEqualTo(FetchResult.Done)
        assertThat(fetching.files.root.exists()).isFalse()
    }

    @Test
    fun `the page of a book kept once the profile closed is not written, and the folder is not made again`() =
        runBlocking {
            val detail = sampleBookDetail("3").copy(coverUrl = keeping.coverUrl(TheBook))
            keeping.files.folders.close()
            val kept = keeping.kept()

            kept.keep(detail)
            val manifest = Recorded.manifest("m4b")
            kept.keepManifest(manifest)

            assertThat(kept.detail(TheBook)).isNull()
            assertThat(kept.coverFile(TheBook)).isNull()
            assertThat(kept.manifest(manifest.bookId)).isNull()
            assertThat(keeping.files.root.exists()).isFalse()
        }
}
