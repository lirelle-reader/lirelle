package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Run on the platform's XML parser, which the EPUB check reads the container with. */
@RunWith(RobolectricTestRunner::class)
class DownloaderEpubTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    private val server get() = fixture.server
    private val text = DownloadFormat.Text

    @Test
    fun `an EPUB is put where the text and the narration both find it`() = runBlocking {
        val book = epubBytes()
        val file = fixture.epub()
        server.enqueue(whole(book).build())

        val result = fixture.fetch(file, text)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(fixture.target(file, text).readBytes()).isEqualTo(book)
        assertThat(fixture.target(file, DownloadFormat.Narration)).isEqualTo(fixture.target(file, text))
    }

    @Test
    fun `a file that is not an EPUB is refused and removed`() = runBlocking {
        val file = fixture.epub()
        server.enqueue(whole("<html>Sign in</html>".toByteArray()).build())

        val result = fixture.fetch(file, text)

        assertThat(result).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))
        assertThat(fixture.part(file, text).exists()).isFalse()
        assertThat(fixture.partVersion(file, text).exists()).isFalse()
        assertThat(fixture.target(file, text).exists()).isFalse()
    }

    @Test
    fun `an archive without a package is not an EPUB either`() = runBlocking {
        val file = fixture.epub()
        val archive = epubBytes("META-INF/container.xml" to "<container/>")
        server.enqueue(whole(archive).build())

        val result = fixture.fetch(file, text)

        assertThat(result).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))
        assertThat(fixture.target(file, text).exists()).isFalse()
    }

    @Test
    fun `a protected EPUB is kept, the reader says what it is when it opens`() = runBlocking {
        val file = fixture.epub()
        server.enqueue(whole(epubBytes("META-INF/rights.xml" to "<rights/>")).build())

        val result = fixture.fetch(file, text)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(fixture.target(file, text).isFile).isTrue()
    }
}
