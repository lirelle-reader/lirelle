package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("1")

/** The updates asked of one book take the place of what is kept in one step, once they are all whole. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapTogetherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val refused = FetchResult.Refused(DownloadFailure.Server(ApiError.ServerFailed))

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 keeps its text and its narration; the server then has another EPUB and another track. */
    private suspend fun QueueFixture.narrationChanged() {
        serve(TheBook, Text, epub())
        serve(TheBook, Narration, epub(), audio("t1", bytes = 30))
        queue.ask(TheBook, setOf(Narration))
        runner.runUntilIdle()
        val epub = epub(bytes = 55, version = "v2")
        serve(TheBook, Text, epub, version = "v2")
        serve(TheBook, Narration, epub, audio("t1", bytes = 35, version = "v2"), version = "v2")
    }

    private suspend fun QueueFixture.assertTextWaitsWhole() {
        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(40)
        assertThat(record(TheBook, Text).version).isEqualTo("v1")
        assertThat(record(TheBook, Narration).version).isEqualTo("v1")
        assertThat(record(TheBook, Narration).files).containsExactly(epub(), audio("t1", bytes = 30)).inOrder()
        assertThat(update(TheBook, Text)?.state).isEqualTo(FormatState.Complete(55))
        assertThat(beside(TheBook, Text, "12.epub").length()).isEqualTo(55)
    }

    @Test
    fun `the text's update waits for the narration's, and the kept copy stays while that one failed`() = queueTest {
        narrationChanged()
        updateFetcher.on("t1") { refused }
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(update(TheBook, Narration)?.state).isInstanceOf(FormatState.Failed::class.java)
        assertTextWaitsWhole()

        queue.retry(TheBook)
        runner.runUntilIdle()

        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(55)
        assertThat(file(TheBook, Narration, "t1").length()).isEqualTo(35)
        assertThat(record(TheBook, Text).version).isEqualTo("v2")
        assertThat(record(TheBook, Narration).version).isEqualTo("v2")
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `the EPUB does not change under a narration whose update was cancelled`() = queueTest {
        narrationChanged()
        updateFetcher.on("t1") { refused }
        askUpdate(TheBook)
        runner.runUntilIdle()

        queue.cancel(TheBook, Narration)

        assertThat(update(TheBook, Narration)?.state).isNull()
        assertTextWaitsWhole()
    }

    @Test
    fun `cancelling the update that was missing lets the whole ones take their place`() = queueTest {
        serve(TheBook, Text, epub())
        serve(TheBook, Audiobook, audio("a1"))
        queue.ask(TheBook, setOf(Text, Audiobook))
        runner.runUntilIdle()
        serve(TheBook, Text, epub(bytes = 55, version = "v2"), version = "v2")
        serve(TheBook, Audiobook, audio("a1", bytes = 70, version = "v2"), version = "v2")
        updateFetcher.on("a1") { refused }
        askUpdate(TheBook)
        runner.runUntilIdle()
        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(40)
        assertThat(update(TheBook, Text)?.state).isEqualTo(FormatState.Complete(55))

        queue.cancel(TheBook, Audiobook)

        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(55)
        assertThat(record(TheBook, Text).version).isEqualTo("v2")
        assertThat(record(TheBook, Text).update).isNull()
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v1")
        assertThat(file(TheBook, Audiobook, "a1").length()).isEqualTo(100)
    }

    @Test
    fun `at the next start an update that is whole still waits for the one that failed`() = queueTest {
        narrationChanged()
        updateFetcher.on("t1") { refused }
        askUpdate(TheBook)
        runner.runUntilIdle()
        val fresh = restartedAs(QueueFixture(it, files.bookDir(TheBook).parentFile!!))

        fresh.runner.runUntilIdle()

        fresh.assertTextWaitsWhole()
    }
}
