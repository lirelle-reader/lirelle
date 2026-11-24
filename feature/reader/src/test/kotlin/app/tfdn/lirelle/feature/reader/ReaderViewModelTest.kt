package app.tfdn.lirelle.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.FakeReaderEngine
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opening a book: from its id to the page, the failures on the way, and giving everything back. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private val ReaderViewModel.phase get() = state.value.phase

    @Test
    fun `it downloads with progress, then reads`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val progress = DownloadProgress(bytesRead = 10, totalBytes = 100)
        harness.cache.states = listOf(CacheState.Downloading(progress), CacheState.Ready(harness.bookFile))
        harness.cache.gate = CompletableDeferred()

        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Downloading(progress))
        assertThat(viewModel.state.value.title).isEqualTo("A Complete Book")

        harness.cache.gate?.complete(Unit)

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
        assertThat(viewModel.state.value.opened).isFalse()
    }

    @Test
    fun `nothing is known while the details are asked`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.library.gate = CompletableDeferred()

        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Downloading(null))
    }

    @Test
    fun `a read-along file is downloaded without its audio, under its id and version`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val files = harness.detail.files.map { if (it.id == FileId("1")) it.copy(contentVersion = "v7") else it }
            harness.detail = harness.detail.copy(files = files)

            harness.viewModel()

            assertThat(harness.cache.opened).containsExactly(CachedBookKey(FileId("1"), "v7") to true)
        }

    @Test
    fun `a plain EPUB is downloaded as it is`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val files = harness.detail.files.map {
            if (it.id == FileId("1")) it.copy(kind = BookFileKind.Text) else it
        }
        harness.detail = harness.detail.copy(files = files)

        harness.viewModel()

        assertThat(harness.cache.opened).containsExactly(harness.key to false)
    }

    @Test
    fun `a book without an EPUB cannot be read`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.detail = harness.detail.copy(files = harness.detail.files.filter { it.format == "M4B" })

        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.NoEpub))
        assertThat(harness.cache.opened).isEmpty()
    }

    @Test
    fun `a file that is not an EPUB is refused and given back to the cache`() = runTest(mainRule.dispatcher) {
        listOf(EpubProblem.NotAnArchive, EpubProblem.NotAnEpub).forEach { problem ->
            val harness = harness()
            harness.epub = EpubOpening.Refused(problem)

            val viewModel = harness.viewModel()

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.NotAnEpub))
            assertThat(harness.cache.released).containsExactly(harness.key)
        }
    }

    @Test
    fun `a protected file is refused and given back to the cache`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.epub = EpubOpening.Refused(EpubProblem.Protected)

        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Protected))
        assertThat(harness.cache.released).containsExactly(harness.key)
    }

    @Test
    fun `a download failure is shown, and retry starts again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.cache.states = listOf(CacheState.Failed(ApiError.ServerUnreachable))
        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Download(ApiError.ServerUnreachable)))

        harness.cache.states = listOf(CacheState.Ready(harness.bookFile))
        viewModel.retry()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
        assertThat(harness.cache.opened).hasSize(2)
        assertThat(harness.cache.released).isEmpty()
    }

    @Test
    fun `details that cannot be fetched are a download failure`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.viewModel()
        harness.library.bookResult = ApiResult.Failure(ApiError.NotFound)

        viewModel.retry()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Download(ApiError.NotFound)))
        // The book of the first opening was given back before starting again.
        assertThat(harness.cache.released).containsExactly(harness.key)
    }

    @Test
    fun `an old web view is said before anything is downloaded`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.webViewVersion = 119

        val viewModel = harness.viewModel()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.OldWebView))
        assertThat(viewModel.state.value.title).isEqualTo("A Complete Book")
        assertThat(harness.cache.opened).isEmpty()
    }

    @Test
    fun `the oldest web view accepted, and one whose version is unknown, open the book`() =
        runTest(mainRule.dispatcher) {
            listOf(120, null).forEach { version ->
                val harness = harness()
                harness.webViewVersion = version

                assertThat(harness.viewModel().phase).isInstanceOf(ReaderPhase.Reading::class.java)
            }
        }

    @Test
    fun `on ready it opens the book at the server's place, as the settings say`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.settings.update { ReaderSettings(theme = ReaderTheme.Sepia, fontSizeStep = 9) }
        harness.repository.positionResult =
            ApiResult.Success(ReadingPosition(FileId("1"), SecondCfi, 42.0, Instant.parse("2026-11-01T08:00:00Z")))
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)

        assertThat(harness.engine.sent).isEmpty()

        harness.engine.emit(PageMessage.Ready)

        val sepia = ReaderTheme.Sepia.colours(systemDark = false)
        assertThat(harness.engine.sent).containsExactly(
            HostMessage.Open(
                base = "/book/first/",
                cfi = SecondCfi,
                fraction = 0.42,
                settings = harness.settings.current.toPageSettings(sepia, false, EnglishNoteLabels),
            ),
        )
        assertThat(viewModel.state.value.colours).isEqualTo(sepia)
    }

    @Test
    fun `a place known by its percentage only opens at that fraction`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.positionResult =
            ApiResult.Success(ReadingPosition(FileId("1"), null, 64.0, Instant.parse("2026-11-01T08:00:00Z")))
        harness.viewModel().onEngine(harness.engine)

        harness.engine.emit(PageMessage.Ready)

        val open = harness.engine.sent.single() as HostMessage.Open
        assertThat(open.cfi).isNull()
        assertThat(open.fraction).isEqualTo(0.64)
    }

    @Test
    fun `a ready page waits for the book to be downloaded`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.cache.gate = CompletableDeferred()
        harness.viewModel().onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        assertThat(harness.engine.sent).isEmpty()

        harness.cache.gate?.complete(Unit)

        assertThat(harness.engine.sent.single()).isInstanceOf(HostMessage.Open::class.java)
    }

    @Test
    fun `a ready page waits for the web view to serve the book`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val engine = FakeReaderEngine(bookBase = null)
        harness.viewModel().onEngine(engine)
        engine.emit(PageMessage.Ready)

        assertThat(engine.sent).isEmpty()

        engine.bookBase.value = "/book/late/"

        assertThat((engine.sent.single() as HostMessage.Open).base).isEqualTo("/book/late/")
    }

    @Test
    fun `nothing is sent to a page that has not said it is ready`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)

        viewModel.updateSettings { it.copy(fontSizeStep = 2) }
        viewModel.nextChapter()
        viewModel.seekTo(0.5)
        viewModel.onWindow(wide = true)

        assertThat(harness.engine.sent).isEmpty()
    }

    @Test
    fun `only open is sent before the book is shown, and what changed meanwhile follows`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)

            viewModel.updateSettings { it.copy(fontSizeStep = 2) }
            viewModel.seekTo(0.5)

            assertThat(harness.engine.sent).hasSize(1)

            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Rtl, fixedLayout = false, title = "T"))

            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ApplySettings(harness.pageSettings))
            assertThat(viewModel.state.value.opened).isTrue()
            assertThat(viewModel.state.value.toc).isEqualTo(SampleToc)
            assertThat(viewModel.state.value.rtl).isTrue()
        }

    @Test
    fun `a book that does not appear in time is an engine failure, and retry asks the page again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)

            advanceTimeBy(OpeningTimeout - 1.milliseconds)
            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
            advanceTimeBy(2.milliseconds)
            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))

            viewModel.retry()

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
            assertThat(harness.engine.sent.filterIsInstance<HostMessage.Open>()).hasSize(2)
            // The same book: it was not asked from the cache again.
            assertThat(harness.cache.opened).hasSize(1)
            assertThat(harness.cache.released).isEmpty()
        }

    @Test
    fun `a web view that never comes is an engine failure too`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().viewModel()

        advanceTimeBy(OpeningTimeout + 1.milliseconds)

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))
    }

    @Test
    fun `a book shown in time is not failed later`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        advanceTimeBy(OpeningTimeout + 1.seconds)

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
        assertThat(viewModel.state.value.opened).isTrue()
    }

    @Test
    fun `a page that cannot open the book is an engine failure`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        harness.engine.emit(PageMessage.Error(PageErrorCode.OpenFailed, "No package document"))

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))
    }

    @Test
    fun `a page that cannot run the engine asks for a newer web view, again after retry`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)

            harness.engine.emit(PageMessage.Error(PageErrorCode.UnsupportedEngine, "Missing CSS nesting"))
            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.OldWebView))

            viewModel.retry()

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.OldWebView))
            assertThat(harness.engine.sent).isEmpty()
        }

    @Test
    fun `a section that fails once the book is shown does not close it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Error(PageErrorCode.SectionFailed, "Missing file"))

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
    }

    @Test
    fun `a new engine after the web view died opens the book again where the reader was`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))
            val sentToTheFirst = harness.engine.sent.size
            val second = FakeReaderEngine(bookBase = "/book/second/")

            viewModel.onEngine(second)

            assertThat(viewModel.state.value.opened).isFalse()
            assertThat(second.sent).isEmpty()

            second.emit(PageMessage.Ready)

            assertThat(second.sent).containsExactly(
                HostMessage.Open("/book/second/", cfi = ThirdCfi, fraction = 0.5, settings = harness.pageSettings),
            )

            second.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            viewModel.nextChapter()

            assertThat(viewModel.state.value.opened).isTrue()
            assertThat(second.sent.last()).isEqualTo(HostMessage.GoTo.Href("two.xhtml"))
            assertThat(harness.engine.sent).hasSize(sentToTheFirst)
        }

    @Test
    fun `a book that kills the web view twice without being shown is an engine failure`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val second = FakeReaderEngine(bookBase = "/book/second/")
            val third = FakeReaderEngine(bookBase = "/book/third/")

            viewModel.onEngine(second)
            second.emit(PageMessage.Ready)
            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))

            viewModel.onEngine(third)
            third.emit(PageMessage.Ready)

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))
            assertThat(third.sent).isEmpty()
            // Back has nothing to close: the reader is left.
            assertThat(viewModel.onBack()).isFalse()

            viewModel.retry()

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
            assertThat(third.sent.single()).isInstanceOf(HostMessage.Open::class.java)
        }

    @Test
    fun `a web view that died is replaced as often as the book is shown in between`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()

            repeat(4) { round ->
                val next = FakeReaderEngine(bookBase = "/book/$round/")
                viewModel.onEngine(next)
                next.emit(PageMessage.Ready)
                next.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            }

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
            assertThat(viewModel.state.value.opened).isTrue()
        }

    @Test
    fun `web views replaced while the book is still downloading are not counted against it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.cache.gate = CompletableDeferred()
            val viewModel = harness.viewModel()
            val last = FakeReaderEngine(bookBase = "/book/last/")

            repeat(3) { viewModel.onEngine(FakeReaderEngine()) }
            viewModel.onEngine(last)
            last.emit(PageMessage.Ready)
            harness.cache.gate?.complete(Unit)

            assertThat(viewModel.phase).isEqualTo(ReaderPhase.Reading(harness.content))
            assertThat(last.sent.single()).isInstanceOf(HostMessage.Open::class.java)
        }

    @Test
    fun `a new engine that never shows the book is an engine failure`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        advanceTimeBy(1.seconds)

        viewModel.onEngine(FakeReaderEngine(bookBase = "/book/second/"))
        advanceTimeBy(OpeningTimeout + 1.milliseconds)

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))
    }

    @Test
    fun `leaving closes the archive and frees the cache once, however often it is said`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = harness.reading() as T
            }
            val viewModel = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
            assertThat(harness.archive.open("a.txt")).isNotNull()

            viewModel.onLeave()
            viewModel.onLeave()
            store.clear()

            assertThat(harness.cache.released).containsExactly(harness.key)
            assertThat(harness.archive.open("a.txt")).isNull()
            assertThat(harness.cache.opened).hasSize(1)
        }

    @Test
    fun `a view model cleared without a word gives the book back too`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = harness.reading() as T
        }
        ViewModelProvider(store, factory)[ReaderViewModel::class.java]

        store.clear()

        assertThat(harness.cache.released).containsExactly(harness.key)
        assertThat(harness.archive.open("a.txt")).isNull()
    }

    @Test
    fun `leaving during the download holds nothing and retries nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.cache.gate = CompletableDeferred()
        val viewModel = harness.viewModel()

        viewModel.onLeave()
        harness.cache.gate?.complete(Unit)
        viewModel.retry()

        assertThat(viewModel.phase).isEqualTo(ReaderPhase.Downloading(null))
        assertThat(harness.cache.opened).hasSize(1)
        assertThat(harness.cache.released).isEmpty()
    }

    @Test
    fun `the level of the screen is in the state, for a brightness that starts from it`() =
        runTest(mainRule.dispatcher) {
            val viewModel = harness().reading()
            assertThat(viewModel.state.value.systemBrightness).isEqualTo(0.5f)

            viewModel.onSystemBrightness(0.4f)
            assertThat(viewModel.state.value.systemBrightness).isEqualTo(0.4f)

            viewModel.onSystemBrightness(Float.NaN)
            assertThat(viewModel.state.value.systemBrightness).isEqualTo(0.4f)

            viewModel.onSystemBrightness(1.5f)
            assertThat(viewModel.state.value.systemBrightness).isEqualTo(1f)
        }

    @Test
    fun `the back arrow asks to leave`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        viewModel.leave()

        assertThat(viewModel.events.first()).isEqualTo(ReaderEvent.Leave)
    }
}
