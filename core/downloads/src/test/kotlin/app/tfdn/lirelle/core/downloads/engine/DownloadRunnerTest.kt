package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRunnerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bookOne = BookId("1")
    private val bookTwo = BookId("2")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    @Test
    fun `the text, then the audiobook, then the narration's tracks, the EPUB they share fetched once`() = queueTest {
        serve(bookOne, Text, epub())
        serve(bookOne, Audiobook, audio("a1"), audio("a2", bytes = 50))
        serve(bookOne, Narration, epub(), audio("t1", bytes = 30))
        queue.ask(bookOne, setOf(Audiobook, Narration))

        runner.runUntilIdle()

        assertThat(fetcher.fetched)
            .containsExactly("1/Text/12.epub", "1/Audiobook/a1", "1/Audiobook/a2", "1/Narration/t1").inOrder()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(150))
        // The EPUB is counted with the text, not twice.
        assertThat(state(bookOne, Narration)).isEqualTo(FormatState.Complete(30))
        assertThat(checkNotNull(registry.book(bookOne)).bytesOnDevice).isEqualTo(220)
    }

    @Test
    fun `the narration's own EPUB comes before the audiobook, and counts with the narration`() = queueTest {
        serve(bookOne, Text, epub("11.epub"))
        serve(bookOne, Audiobook, audio("a1"))
        serve(bookOne, Narration, epub("31.epub", bytes = null), audio("t1", bytes = 30))
        queue.ask(bookOne, setOf(Audiobook, Narration))

        runner.runUntilIdle()

        assertThat(fetcher.fetched)
            .containsExactly("1/Text/11.epub", "1/Narration/31.epub", "1/Audiobook/a1", "1/Narration/t1").inOrder()
        assertThat(state(bookOne, Narration)).isEqualTo(FormatState.Complete(30 + FakeFetcher.UnknownSize))
    }

    @Test
    fun `the record keeps what the format is made of`() = queueTest {
        serve(bookOne, Audiobook, audio("a1"), version = "rev-3")
        queue.ask(bookOne, setOf(Audiobook))
        it.advanceTimeBy(2_000)

        runner.runUntilIdle()

        val book = checkNotNull(registry.book(bookOne))
        val record = book.formats.single()
        assertThat(record.files).containsExactly(audio("a1"))
        assertThat(record.version).isEqualTo("rev-3")
        assertThat(record.fileId).isEqualTo(FileId("12"))
        assertThat(book.downloadedAt).isEqualTo(now())
    }

    @Test
    fun `not enough room fails the format before any request, saying how much is missing`() = queueTest {
        serveBook(bookOne)
        freeBytes = 60
        queue.ask(bookOne, setOf(Text, Audiobook))

        runner.runUntilIdle()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Failed(DownloadFailure.NoSpace(40), 0))
        assertThat(fetcher.fetched).containsExactly("1/Text/1.epub")
    }

    @Test
    fun `a refused file fails its format, and the next book goes on`() = queueTest {
        serveBook(bookOne)
        serveBook(bookTwo)
        val refusal = DownloadFailure.Server(ApiError.ServerFailed)
        fetcher.on("a1") { FetchResult.Refused(refusal) }
        queue.ask(bookOne, setOf(Audiobook))
        it.advanceTimeBy(1)
        queue.ask(bookTwo, setOf(Audiobook))

        runner.runUntilIdle()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Failed(refusal, 0))
        assertThat(state(bookTwo, Audiobook)).isEqualTo(FormatState.Complete(100))
        assertThat(fetcher.fetched).containsExactly("1/Audiobook/a1", "2/Audiobook/a2").inOrder()
    }

    @Test
    fun `a book the server no longer has fails with the server's reason`() = queueTest {
        val moved = ApiError.Moved(BookId("9"))
        plans.answers[bookOne to Text] = ApiResult.Failure(moved)
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Failed(DownloadFailure.Server(moved), 0))
    }

    @Test
    fun `a cut download waits, and goes on after 5 s from what was received`() = queueTest {
        serve(bookOne, Audiobook, audio("a1"), audio("a2"))
        fetcher.on("a2") { fetch ->
            fetch.onProgress(30)
            files.part(bookOne, Audiobook, "a2").apply { checkNotNull(parentFile).mkdirs() }.writeBytes(ByteArray(30))
            FetchResult.Cut(30, ApiError.ServerUnreachable)
        }
        queue.ask(bookOne, setOf(Audiobook))
        keepRunning()
        it.runCurrent()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 130, 200))
        it.advanceTimeBy(4_999)
        it.runCurrent()
        assertThat(fetcher.fetched).hasSize(2)

        it.advanceTimeBy(1)
        it.runCurrent()

        // The file already in place is not fetched again; the format is planned again.
        assertThat(fetcher.fetched).containsExactly("1/Audiobook/a1", "1/Audiobook/a2", "1/Audiobook/a2").inOrder()
        assertThat(plans.asked).hasSize(2)
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(200))
    }

    @Test
    fun `cuts in a row wait longer and longer`() = queueTest {
        serveBook(bookOne)
        repeat(3) { fetcher.on("a1") { FetchResult.Cut(0, ApiError.ServerUnreachable) } }
        queue.ask(bookOne, setOf(Audiobook))
        keepRunning()
        it.runCurrent()

        it.advanceTimeBy(5_000)
        it.runCurrent()
        assertThat(fetcher.fetched).hasSize(2)
        it.advanceTimeBy(29_999)
        it.runCurrent()
        assertThat(fetcher.fetched).hasSize(2)
        it.advanceTimeBy(1)
        it.runCurrent()
        assertThat(fetcher.fetched).hasSize(3)
        it.advanceTimeBy(120_000)
        it.runCurrent()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
    }

    @Test
    fun `a cut download goes on as soon as the network changes`() = queueTest {
        serveBook(bookOne)
        fetcher.on("a1") { FetchResult.Cut(0, ApiError.ServerUnreachable) }
        queue.ask(bookOne, setOf(Audiobook))
        keepRunning()
        it.runCurrent()
        network.value = NoNetwork
        it.runCurrent()
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, 100))

        network.value = Wifi
        it.runCurrent()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
    }

    @Test
    fun `a file whose version changed is fetched again, the others are not`() = queueTest {
        serve(bookOne, Audiobook, audio("a1"), audio("a2"))
        fetcher.on("a2") { FetchResult.Cut(0, ApiError.ServerUnreachable) }
        queue.ask(bookOne, setOf(Audiobook))
        keepRunning()
        it.runCurrent()
        serve(bookOne, Audiobook, audio("a1", bytes = 120, version = "v2"), audio("a2"))

        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(fetcher.fetched)
            .containsExactly("1/Audiobook/a1", "1/Audiobook/a2", "1/Audiobook/a1", "1/Audiobook/a2").inOrder()
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(220))
    }

    @Test
    fun `without a network the queue waits, and goes on alone when it is back`() = queueTest {
        serveBook(bookOne)
        network.value = NoNetwork
        queue.ask(bookOne, setOf(Text, Audiobook))
        keepRunning()
        it.runCurrent()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, null))
        assertThat(fetcher.fetched).isEmpty()

        network.value = Wifi
        it.runCurrent()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
    }

    /** As the profile's module wires it: the queue's network is the profile's, not the device's. */
    @Test
    fun `while the session waits for a sign-in the queue waits, and goes on alone once somebody signed in`() =
        queueTest {
            val signInNeeded = MutableStateFlow(true)
            val profile = ProfileNetwork(MutableStateFlow(Wifi), emptyFlow(), signInNeeded, it.backgroundScope)
            it.backgroundScope.launch { profile.state.collect { state -> network.value = state } }
            serveBook(bookOne)
            queue.ask(bookOne, setOf(Text, Audiobook))
            keepRunning()
            it.runCurrent()
            assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, null))
            assertThat(fetcher.fetched).isEmpty()

            signInNeeded.value = false
            it.runCurrent()

            assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
            assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
        }

    @Test
    fun `the setting lets the queue use a metered network`() = queueTest {
        serveBook(bookOne)
        network.value = Mobile
        queue.ask(bookOne, setOf(Text))
        keepRunning()
        it.runCurrent()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null))

        allowMetered.value = true
        it.runCurrent()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
    }

    @Test
    fun `a network that becomes metered in the middle of a file stops it there`() = queueTest {
        serveBook(bookOne)
        fetcher.on("a1") { fetch ->
            fetch.onProgress(10)
            network.value = Mobile
            delay(60_000)
            store(fetch)
        }
        queue.ask(bookOne, setOf(Audiobook))

        runner.runUntilIdle()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 10, 100))
        assertThat(file(bookOne, Audiobook, "a1").exists()).isFalse()
    }

    @Test
    fun `a file whole in a cache is copied from there, not fetched`() = queueTest {
        serveBook(bookOne)
        caches += CachedFiles { file: PlannedFile, into: File ->
            (file.name == "a1").also { cached -> if (cached) into.writeBytes(ByteArray(100)) }
        }
        queue.ask(bookOne, setOf(Text, Audiobook))

        runner.runUntilIdle()

        assertThat(fetcher.fetched).containsExactly("1/Text/1.epub")
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
        assertThat(file(bookOne, Audiobook, "a1").length()).isEqualTo(100)
        assertThat(file(bookOne, Audiobook, "a1").parentFile?.list()).asList().containsExactly("a1")
    }

    @Test
    fun `progress is written at most every half second, and when a file ends`() = queueTest {
        serveBook(bookOne)
        fetcher.on("a1") { fetch ->
            repeat(20) { step ->
                fetch.onProgress(step * 5L)
                delay(100)
            }
            store(fetch)
        }
        queue.ask(bookOne, setOf(Audiobook))
        registry.states.clear()

        runner.runUntilIdle()

        val progress = registry.states.map { it.second }.filterIsInstance<FormatState.Downloading>()
        // When the format starts; the first bytes told, then what was last told every 500 ms; when the file ends.
        assertThat(progress.map { it.receivedBytes }).containsExactly(0L, 0L, 20L, 45L, 70L, 95L, 100L).inOrder()
        assertThat(registry.states.last().second).isEqualTo(FormatState.Complete(100))
    }

    @Test
    fun `two runs at once never fetch the same file twice`() = queueTest {
        serve(bookOne, Audiobook, audio("a1"), audio("a2"))
        fetcher.on("a1") { fetch ->
            delay(1_000)
            store(fetch)
        }
        queue.ask(bookOne, setOf(Audiobook))

        listOf(it.async { runner.runUntilIdle() }, it.async { runner.runUntilIdle() }).awaitAll()

        assertThat(fetcher.fetched).containsExactly("1/Audiobook/a1", "1/Audiobook/a2").inOrder()
    }
}
