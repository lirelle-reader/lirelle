package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.Downloader
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.engine.RangedFile
import app.tfdn.lirelle.core.downloads.engine.TestRefusals
import app.tfdn.lirelle.core.downloads.engine.bytes
import app.tfdn.lirelle.core.downloads.engine.testLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val NewLength = 150_000

private val TheBook = BookId("1")

/** The update's files come from a server through the real downloader, into a real folder. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateOverHttpTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val content = bytes(NewLength)

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun httpTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        val fixture = QueueFixture(this, folder.newFolder("downloads")) { places, io ->
            val locations = testLocations(server.url("/").toString().trimEnd('/'))
            Downloader(OkHttpClient(), places, locations, TestRefusals(), io)
        }
        fixture.test(this)
    }

    /** Book 1 keeps an audiobook of two files; the server then has another version of the second. */
    private suspend fun QueueFixture.audiobookChanged() {
        serve(TheBook, Audiobook, audio("a1"), audio("a2"))
        queue.ask(TheBook, setOf(Audiobook))
        runner.runUntilIdle()
        val asset = FileSource.AudioAsset("a2")
        val changed = PlannedFile("a2", asset, "v2", NewLength.toLong(), resumable = true, kind = FileKind.Audio)
        serve(TheBook, Audiobook, audio("a1"), changed, version = "v2")
    }

    @Test
    fun `the changed file is received beside the kept one, and takes its place when the book closes`() = httpTest {
        audiobookChanged()
        server.dispatcher = RangedFile(content)
        remover.hold(TheBook)
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(beside(TheBook, Audiobook, "a2").readBytes()).isEqualTo(content)
        assertThat(file(TheBook, Audiobook, "a2").readBytes()).isEqualTo(ByteArray(100))
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(200))

        remover.release(TheBook)
        it.runCurrent()

        assertThat(file(TheBook, Audiobook, "a2").readBytes()).isEqualTo(content)
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(100L + NewLength))
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `an update cut on the way goes on from what it received beside the kept file`() = httpTest {
        audiobookChanged()
        val served = RangedFile(content, cuts = 1)
        server.dispatcher = served
        askUpdate(TheBook)
        keepRunning()
        it.runCurrent()
        val received = files.updates.part(TheBook, Audiobook, "a2").length()
        assertThat(file(TheBook, Audiobook, "a2").readBytes()).isEqualTo(ByteArray(100))
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v1")

        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(served.ranges).containsExactly(null, "bytes=$received-").inOrder()
        assertThat(file(TheBook, Audiobook, "a2").readBytes()).isEqualTo(content)
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v2")
    }
}
