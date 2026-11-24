package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** "Delete all downloads", whoever asks: what the runner is doing is stopped first, and writes nothing after. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAllHaltsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `what is being fetched is stopped first, and writes nothing after the deletion`() = runTest {
        val root = folder.newFolder("downloads")
        val steps = mutableListOf<String>()
        val fixture = QueueFixture(this, root)
        val book = BookId("1")
        fixture.serveBook(book)
        // What notices late that it was stopped, and still writes into the book's folder.
        fixture.fetcher.on("a1") { fetch ->
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(1_000) }
                store(fetch)
                steps += "fetch ended"
            }
        }
        fixture.queue.ask(book, setOf(Text, Audiobook))
        fixture.keepRunning()
        runCurrent()
        assertThat(fixture.state(book, Audiobook)).isInstanceOf(FormatState.Downloading::class.java)

        launch {
            fixture.remover.deleteAll()
            steps += "deleted"
        }
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(steps).containsExactly("fetch ended", "deleted").inOrder()
        assertThat(root.walkTopDown().filter(File::isFile).toList()).isEmpty()
        assertThat(fixture.registry.books.first()).isEmpty()
    }
}
