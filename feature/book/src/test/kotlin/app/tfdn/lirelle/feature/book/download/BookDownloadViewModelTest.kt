package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.engine.offeredFormats
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BookDownloadViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val removals = FakeRemovals()
    private val updates = FakeDownloadUpdates()
    private val pages = FakeKeptPages()
    private val settings = FakeDownloadSettings()
    private val network = MutableStateFlow(Wifi)
    private val canDownload = MutableStateFlow(true)
    private var freeBytes = 41 * Gb

    /** Text (inside the narration's file), audiobook and narration. */
    private val threeFormats = sampleBookDetail(id = "12")

    /** An audiobook and nothing else. */
    private val oneFormat = threeFormats.copy(
        media = MediaProfile.AudioOnly,
        files = threeFormats.files.filter { it.kind == BookFileKind.Audio },
    )

    private fun viewModel(book: BookDetail? = threeFormats): BookDownloadViewModel {
        val surroundings = DownloadSurroundings(network, canDownload, settings) { freeBytes }
        return BookDownloadViewModel("12", downloads, removals, updates, surroundings, pages)
            .also { viewModel -> book?.let { viewModel.show(it) } }
    }

    @Test
    fun `the samples offer what the tests say`() {
        assertThat(threeFormats.offeredFormats().map { it.format }).containsExactly(Text, Audiobook, Narration)
        assertThat(oneFormat.offeredFormats().map { it.format }).containsExactly(Audiobook)
    }

    @Test
    fun `a book with one format is asked at once`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(oneFormat)

        viewModel.onTile()

        assertThat(downloads.calls).containsExactly("ask 12 Audiobook")
        assertThat(viewModel.state.value.sheet).isNull()
    }

    @Test
    fun `a book that is asked keeps its page at once, before anything of it is fetched`() =
        runTest(mainRule.dispatcher) {
            val reading = BookReading(42, lastReadAt = null, totalSeconds = 0, sessionCount = 0)
            val viewModel = viewModel(book = null)
            viewModel.show(oneFormat, reading)
            network.value = Mobile

            viewModel.onTile()

            assertThat(downloads.calls).containsExactly("ask 12 Audiobook")
            assertThat(pages.keeps).containsExactly(oneFormat to reading)
        }

    @Test
    fun `nothing is kept of a book whose download is refused`() = runTest(mainRule.dispatcher) {
        downloads.refusal = AskRefusal.NotAllowed
        val viewModel = viewModel(oneFormat)

        viewModel.onTile()

        assertThat(pages.keeps).isEmpty()
    }

    @Test
    fun `a book with several formats opens the sheet, with the space left`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onTile()

        val sheet = viewModel.state.value.sheet
        assertThat(downloads.calls).isEmpty()
        assertThat(sheet?.situation?.offered).isEqualTo(threeFormats.offeredFormats())
        assertThat(sheet?.freeBytes).isEqualTo(41 * Gb)
        assertThat(sheet?.waitsForWifi).isFalse()
    }

    @Test
    fun `the sheet follows the download while it is open, and closes`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTile()

        downloads.kept.value = downloaded(record(Audiobook, FormatState.Downloading(1 * Mb, 310 * Mb)))

        assertThat(viewModel.state.value.sheet?.situation?.records?.map { it.format }).containsExactly(Audiobook)
        viewModel.closeSheet()
        assertThat(viewModel.state.value.sheet).isNull()
    }

    @Test
    fun `on a metered network without the setting, the sheet says the download will wait`() =
        runTest(mainRule.dispatcher) {
            network.value = Mobile
            val viewModel = viewModel()
            viewModel.onTile()
            assertThat(viewModel.state.value.sheet?.waitsForWifi).isTrue()

            settings.update { it.copy(allowMetered = true) }

            assertThat(viewModel.state.value.sheet?.waitsForWifi).isFalse()
        }

    @Test
    fun `a downloaded book, or one in the queue, opens the sheet`() = runTest(mainRule.dispatcher) {
        listOf(FormatState.Complete(310 * Mb), FormatState.Queued).forEach { state ->
            downloads.kept.value = downloaded(record(Audiobook, state))
            val viewModel = viewModel(oneFormat)

            viewModel.onTile()

            assertThat(viewModel.state.value.sheet).isNotNull()
        }
        assertThat(downloads.calls).isEmpty()
    }

    @Test
    fun `the tile follows what the device keeps`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(oneFormat)
        assertThat(viewModel.state.value.tile).isEqualTo(TileState.NotDownloaded)
        assertThat(viewModel.state.value.enabled).isTrue()

        downloads.kept.value = downloaded(record(Audiobook, FormatState.Downloading(31 * Mb, 310 * Mb)))
        assertThat(viewModel.state.value.tile).isEqualTo(TileState.Downloading(10, Audiobook, 31 * Mb, 310 * Mb))

        downloads.kept.value = downloaded(complete(Audiobook, 310 * Mb))
        assertThat(viewModel.state.value.tile).isEqualTo(TileState.Downloaded)
    }

    @Test
    fun `a book with nothing to keep has no download to ask`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(threeFormats.copy(files = emptyList()))

        viewModel.onTile()

        assertThat(viewModel.state.value.enabled).isFalse()
        assertThat(viewModel.state.value.sheet).isNull()
        assertThat(downloads.calls).isEmpty()
    }

    @Test
    fun `a PDF and a Kindle book are asked as the text of an EPUB is, and their tile follows the device`() =
        runTest(mainRule.dispatcher) {
            listOf("PDF", "MOBI", "AZW", "AZW3", "FB2").forEach { format ->
                downloads.calls.clear()
                downloads.kept.value = null
                val viewModel = viewModel(samplePagedDetail(format, id = "12"))
                assertThat(viewModel.state.value.enabled).isTrue()
                assertThat(viewModel.state.value.tile).isEqualTo(TileState.NotDownloaded)

                viewModel.onTile()
                assertThat(downloads.calls).containsExactly("ask 12 Text")

                downloads.kept.value = downloaded(complete(Text, 5 * Mb))
                assertThat(viewModel.state.value.tile).isEqualTo(TileState.Downloaded)
                assertThat(viewModel.state.value.keepsText).isTrue()
            }
        }

    @Test
    fun `a comic and a DjVu are asked as a text is`() = runTest(mainRule.dispatcher) {
        listOf("CBZ", "CBR", "CB7", "DJVU").forEach { format ->
            downloads.calls.clear()
            val viewModel = viewModel(samplePagedDetail(format, id = "12"))

            viewModel.onTile()

            assertThat(viewModel.state.value.enabled).isTrue()
            assertThat(downloads.calls).containsExactly("ask 12 Text")
        }
    }

    @Test
    fun `the line cancels a download under way`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(record(Audiobook, FormatState.Downloading(1 * Mb, 310 * Mb)))

        viewModel().onLineAction()

        assertThat(downloads.calls).containsExactly("cancel 12")
    }

    @Test
    fun `the line downloads anyway what waits for Wi-Fi`() = runTest(mainRule.dispatcher) {
        network.value = Mobile
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 0, totalBytes = 310 * Mb)
        downloads.kept.value = downloaded(record(Audiobook, waiting))

        viewModel().onLineAction()

        assertThat(downloads.calls).containsExactly("allowAnyNetworkNow")
    }

    @Test
    fun `the line retries what failed`() = runTest(mainRule.dispatcher) {
        val failed = FormatState.Failed(DownloadFailure.Damaged, receivedBytes = 0)
        downloads.kept.value = downloaded(record(Audiobook, failed))

        viewModel().onLineAction()

        assertThat(downloads.calls).containsExactly("retry 12")
    }

    @Test
    fun `what is asked is asked with the sizes the page knows`() = runTest(mainRule.dispatcher) {
        val sizes = threeFormats.offeredFormats().associate { it.format to it.estimatedBytes }
        val viewModel = viewModel()
        viewModel.onTile()

        viewModel.apply(SheetPlan(toDownload = setOf(Text, Narration)))

        // The text of a read-along file has no size of its own.
        assertThat(sizes[Text]).isNull()
        assertThat(downloads.sizes).containsExactly(mapOf(Narration to sizes[Narration]))
    }

    @Test
    fun `a book read by pages is asked with the name of its file, a reflowable text as the text`() =
        runTest(mainRule.dispatcher) {
            listOf("PDF", "DJVU").forEach { format -> viewModel(samplePagedDetail(format, id = "12")).onTile() }
            viewModel(sampleComicDetail(id = "12")).onTile()
            val viewModel = viewModel()
            viewModel.onTile()
            viewModel.apply(SheetPlan(toDownload = setOf(Text, Audiobook)))

            assertThat(downloads.names)
                .containsExactly(TextName.Pdf, TextName.Djvu, TextName.Cbz, TextName.Text).inOrder()
        }

    @Test
    fun `the only format of a book is asked with its size`() = runTest(mainRule.dispatcher) {
        val size = oneFormat.offeredFormats().single().estimatedBytes

        viewModel(oneFormat).onTile()

        assertThat(size).isNotNull()
        assertThat(downloads.sizes).containsExactly(mapOf(Audiobook to size))
    }

    @Test
    fun `the sheet downloads what was checked and closes`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTile()

        viewModel.apply(SheetPlan(toDownload = setOf(Text, Narration), downloadBytes = 48 * Mb))

        assertThat(downloads.calls).containsExactly("ask 12 Text+Narration")
        assertThat(removals.calls).isEmpty()
        assertThat(viewModel.state.value.sheet).isNull()
    }

    @Test
    fun `the sheet deletes what is whole and cancels what is not`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(complete(Text), record(Audiobook, FormatState.Queued))
        val viewModel = viewModel()
        viewModel.onTile()

        viewModel.apply(SheetPlan(toDelete = setOf(Text, Audiobook), deleteBytes = 3 * Mb))

        assertThat(removals.calls).containsExactly("delete 12 Text")
        assertThat(downloads.calls).containsExactly("cancel 12 Audiobook")
        assertThat(viewModel.state.value.inUse).isFalse()
    }

    @Test
    fun `a book in use is not deleted, which is said once`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(complete(Text))
        removals.result = DeleteResult.InUse
        val viewModel = viewModel()
        viewModel.onTile()

        viewModel.apply(SheetPlan(toDownload = setOf(Audiobook), toDelete = setOf(Text), deleteBytes = 3 * Mb))

        assertThat(viewModel.state.value.inUse).isTrue()
        assertThat(viewModel.state.value.sheet).isNull()
        // Nothing of the plan is done.
        assertThat(downloads.calls).isEmpty()
        viewModel.inUseShown()
        assertThat(viewModel.state.value.inUse).isFalse()
    }

    @Test
    fun `without the permission the tile is disabled and nothing is asked`() = runTest(mainRule.dispatcher) {
        canDownload.value = false
        val viewModel = viewModel(oneFormat)

        viewModel.onTile()

        assertThat(viewModel.state.value.tile).isEqualTo(TileState.NotAllowed)
        assertThat(viewModel.state.value.allowed).isFalse()
        assertThat(viewModel.state.value.enabled).isFalse()
        assertThat(downloads.calls).isEmpty()
    }

    @Test
    fun `without the permission a downloaded book opens a sheet that only removes`() = runTest(mainRule.dispatcher) {
        canDownload.value = false
        downloads.kept.value = downloaded(complete(Text))
        val viewModel = viewModel()

        viewModel.onTile()

        val state = viewModel.state.value
        assertThat(state.allowed).isFalse()
        assertThat(state.enabled).isTrue()
        assertThat(state.sheet?.situation?.canDownload).isFalse()
    }

    @Test
    fun `the page knows whether the device keeps the text whole`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.keepsText).isFalse()

        downloads.kept.value = downloaded(complete(Audiobook))
        assertThat(viewModel.state.value.keepsText).isFalse()

        downloads.kept.value = downloaded(complete(Text))
        assertThat(viewModel.state.value.keepsText).isTrue()
    }

    @Test
    fun `the first download asks for the notifications, once`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(oneFormat)

        viewModel.onTile()
        // Asked without waiting for the answer.
        assertThat(downloads.calls).containsExactly("ask 12 Audiobook")
        assertThat(viewModel.state.value.askNotifications).isTrue()

        viewModel.notificationsHandled(asked = true)
        assertThat(viewModel.state.value.askNotifications).isFalse()
        assertThat(settings.settings.value.notificationsAsked).isTrue()

        viewModel.apply(SheetPlan(toDownload = setOf(Audiobook)))
        assertThat(viewModel.state.value.askNotifications).isFalse()
    }

    @Test
    fun `notifications already allowed are not counted as asked`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(oneFormat)
        viewModel.onTile()

        viewModel.notificationsHandled(asked = false)

        assertThat(viewModel.state.value.askNotifications).isFalse()
        assertThat(settings.settings.value.notificationsAsked).isFalse()
    }

    @Test
    fun `a download that is refused asks for nothing`() = runTest(mainRule.dispatcher) {
        downloads.refusal = AskRefusal.NotAllowed
        val viewModel = viewModel(oneFormat)

        viewModel.onTile()

        assertThat(viewModel.state.value.askNotifications).isFalse()
    }

    @Test
    fun `an update that is available is started from the line`() = runTest(mainRule.dispatcher) {
        downloads.kept.value = downloaded(updatable(Audiobook))
        val viewModel = viewModel()
        assertThat(viewModel.state.value.tile).isEqualTo(TileState.Downloaded)
        assertThat(viewModel.state.value.update).isEqualTo(UpdateState.Available(1_080 * Mb))

        viewModel.onLineAction()

        assertThat(updates.calls).containsExactly("update 12")
        assertThat(downloads.calls).isEmpty()
    }

    @Test
    fun `an update under way or in the queue is cancelled from the line`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        listOf(FormatState.Downloading(receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb), FormatState.Queued)
            .forEach { state ->
                downloads.kept.value = downloaded(updatable(Audiobook, state))
                viewModel.onLineAction()
            }

        assertThat(downloads.calls).containsExactly("cancel 12", "cancel 12")
        assertThat(updates.calls).isEmpty()
    }

    @Test
    fun `an update that waits for Wi-Fi is let go on from the line`() = runTest(mainRule.dispatcher) {
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb)
        downloads.kept.value = downloaded(updatable(Audiobook, waiting))
        val viewModel = viewModel()

        viewModel.onLineAction()

        assertThat(downloads.calls).containsExactly("allowAnyNetworkNow")
    }

    @Test
    fun `an update that failed is tried again from the line`() = runTest(mainRule.dispatcher) {
        downloads.kept.value =
            downloaded(updatable(Audiobook, FormatState.Failed(DownloadFailure.NotAnEpub, receivedBytes = 0)))
        val viewModel = viewModel()

        viewModel.onLineAction()

        assertThat(downloads.calls).containsExactly("retry 12")
    }

    @Test
    fun `a book kept as the server has it, or whose update is ready, has no action`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        listOf(complete(Audiobook), updatable(Audiobook, FormatState.Complete(Mb))).forEach { format ->
            downloads.kept.value = downloaded(format)
            viewModel.onLineAction()
        }

        assertThat(downloads.calls).isEmpty()
        assertThat(updates.calls).isEmpty()
    }
}
