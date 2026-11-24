package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.cache.CacheState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The book's place in the cache: held from the moment it is ready, given back whenever nobody gets the book. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookOpenerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        library.bookResult = ApiResult.Success(detail)
    }

    private fun ReaderHarness.opener(epubs: EpubOpener = EpubOpener { epub }) =
        BookOpener(library, cache, epubs, { webViewVersion }, kept, downloads)

    @Test
    fun `a book that comes out ready is held once, until it is closed`() = runTest {
        val harness = harness()

        val ready = harness.opener().open(BookId("12")).toList().last() as OpeningStep.Ready

        assertThat(harness.cache.held).containsExactly(harness.key)
        assertThat(harness.cache.released).isEmpty()

        ready.book.close()
        ready.book.close()

        assertThat(harness.cache.released).containsExactly(harness.key)
    }

    @Test
    fun `the cache is asked for the file of the book opened`() = runTest {
        val harness = harness()

        harness.opener().open(BookId("12")).toList()

        assertThat(harness.cache.books).containsExactly(BookId("12"))
        assertThat(harness.cache.opened.map { it.first }).containsExactly(harness.key)
    }

    @Test
    fun `a file that is refused, or a download that fails, leaves nothing held`() = runTest {
        val refused = harness()
        refused.epub = EpubOpening.Refused(EpubProblem.NotAnArchive)
        refused.opener().open(BookId("12")).toList()
        assertThat(refused.cache.stillHeld).isEmpty()

        val failed = harness()
        failed.cache.states = listOf(CacheState.Failed(ApiError.ServerUnreachable))
        failed.opener().open(BookId("12")).toList()
        assertThat(failed.cache.held).isEmpty()
    }

    @Test
    fun `a file whose reading throws is refused as not an EPUB, and nothing is held`() = runTest {
        val harness = harness()
        // What the platform's zip and XML readers throw on some malformed files.
        val opener = harness.opener { throw IllegalArgumentException("MALFORMED") }

        val steps = opener.open(BookId("12")).toList()

        assertThat(steps.last()).isEqualTo(OpeningStep.Failed(ReaderFailure.NotAnEpub))
        assertThat(harness.cache.held).containsExactly(harness.key)
        assertThat(harness.cache.stillHeld).isEmpty()
    }

    @Test
    fun `a file that cannot be a book is removed from the cache, so that it is fetched afresh`() = runTest {
        listOf(EpubProblem.NotAnArchive, EpubProblem.NotAnEpub).forEach { problem ->
            val harness = harness()
            harness.epub = EpubOpening.Refused(problem)

            harness.opener().open(BookId("12")).toList()

            assertThat(harness.cache.discarded).containsExactly(harness.key)
            assertThat(harness.cache.stillHeld).isEmpty()
        }

        val thrown = harness()
        thrown.opener { throw IllegalArgumentException("MALFORMED") }.open(BookId("12")).toList()
        assertThat(thrown.cache.discarded).containsExactly(thrown.key)
    }

    @Test
    fun `a protected book and a book that opens stay in the cache`() = runTest {
        val protected = harness()
        protected.epub = EpubOpening.Refused(EpubProblem.Protected)
        protected.opener().open(BookId("12")).toList()
        assertThat(protected.cache.discarded).isEmpty()

        val fine = harness()
        fine.opener().open(BookId("12")).toList()
        assertThat(fine.cache.discarded).isEmpty()
    }

    @Test
    fun `a reader cancelled while the file is checked gives the book back`() = runTest {
        val harness = harness()
        val checking = CompletableDeferred<Unit>()
        val opener = harness.opener {
            checking.await()
            harness.epub
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { opener.open(BookId("12")).collect { } }
        assertThat(harness.cache.held).containsExactly(harness.key)

        job.cancel()
        runCurrent()

        assertThat(harness.cache.stillHeld).isEmpty()
    }

    @Test
    fun `a reader cancelled as the book is handed over gives it back, closed`() = runTest {
        val harness = harness()
        lateinit var job: Job
        // The file is checked and open, and the reader is left before it is told so.
        val opener = harness.opener {
            job.cancel()
            harness.epub
        }
        val steps = mutableListOf<OpeningStep>()
        job = launch { opener.open(BookId("12")).collect { steps += it } }
        runCurrent()

        assertThat(steps.filterIsInstance<OpeningStep.Ready>()).isEmpty()
        assertThat(harness.cache.held).containsExactly(harness.key)
        assertThat(harness.cache.stillHeld).isEmpty()
        assertThat(harness.archive.open("a.txt")).isNull()
    }

    @Test
    fun `a reader cancelled after it took the book closes it itself, once`() = runTest {
        val harness = harness()
        val taken = CompletableDeferred<OpenedBook>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            harness.opener().open(BookId("12")).collect { step ->
                if (step is OpeningStep.Ready) {
                    taken.complete(step.book)
                    CompletableDeferred<Unit>().await()
                }
            }
        }

        job.cancel()
        runCurrent()
        taken.await().close()

        assertThat(harness.cache.released).containsExactly(harness.key)
    }
}
