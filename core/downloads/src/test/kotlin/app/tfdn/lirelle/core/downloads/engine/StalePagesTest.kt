package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.update.askUpdate
import app.tfdn.lirelle.core.downloads.update.update
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PageBytes = FakeFetcher.UnknownSize

/** The process ends there, as the system ends it. */
private class Ended : RuntimeException()

/**
 * The pages a version left on the device are never taken for those of the next: not even when the process
 * ends between the moment the record says the new version and the next thing the run does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StalePagesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File

    private val bookOne = BookId("1")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        root = folder.newFolder("downloads")
        QueueFixture(this, root).test(this)
    }

    /** Runs until a record, or its update, says [version]; the process ends on that write. */
    private suspend fun QueueFixture.runUntilItSays(version: String) {
        registry.afterWrite = { book ->
            if (book?.formats.orEmpty().any { it.version == version || it.update?.version == version }) throw Ended()
        }
        val ended = runCatching { runner.runUntilIdle() }.exceptionOrNull()
        check(ended is Ended) { "The run was to end as the record took $version: $ended" }
        registry.afterWrite = {}
    }

    /** Another process over the same folder and what the registry said. */
    private suspend fun QueueFixture.restarted(scope: TestScope): QueueFixture {
        val fresh = QueueFixture(scope, root)
        registry.book(bookOne)?.let { book -> fresh.registry.update(bookOne) { book } }
        return fresh
    }

    @Test
    fun `a download ended as its record took a new version fetches every page of that version`() = queueTest {
        serveAlbum(bookOne, 4)
        fetcher.on("2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        check(keptPages(bookOne) == listOf("0", "1"))
        serveAlbum(bookOne, 3, version = "v2")
        queue.retry(bookOne)

        runUntilItSays("v2")
        val fresh = restarted(it)
        fresh.serveAlbum(bookOne, 3, version = "v2")
        fresh.runner.runUntilIdle()

        assertThat(fresh.fetcher.fetched).containsExactly("1/Text/0", "1/Text/1", "1/Text/2").inOrder()
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(3, 3))
        assertThat(fresh.state(bookOne, Text)).isEqualTo(FormatState.Complete(3 * PageBytes))
    }

    @Test
    fun `an update ended as it took a newer version fetches every page of that version`() = queueTest {
        serveAlbum(bookOne, 4)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        serveAlbum(bookOne, 3, version = "v2")
        updateFetcher.on("2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        askUpdate(bookOne)
        runner.runUntilIdle()
        check(update(bookOne, Text)?.pages == PageCount(2, 3))
        serveAlbum(bookOne, 3, version = "v3")
        queue.retry(bookOne)

        runUntilItSays("v3")
        val fresh = restarted(it)
        fresh.serveAlbum(bookOne, 3, version = "v3")
        fresh.runner.runUntilIdle()

        assertThat(fresh.updateFetcher.fetched).containsExactly("1/Text/0", "1/Text/1", "1/Text/2").inOrder()
        assertThat(fresh.text(bookOne).version).isEqualTo("v3")
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(3, 3))
    }
}
