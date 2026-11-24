package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** The session waits for what a play button resumes: it is always answered, whatever becomes of the reading. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResumptionAnswerTest {

    /** A store that reads as [read] says. */
    private class Store(private val read: suspend () -> LastBook?) : LastBookStore {
        override suspend fun get(): LastBook? = read()

        override suspend fun put(book: LastBook) = Unit

        override suspend fun clear() = Unit
    }

    private fun TestScope.service() = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))

    private fun assertNoBook(answer: java.util.concurrent.Future<*>) {
        assertThat(answer.isDone).isTrue()
        val failure = assertThrows(ExecutionException::class.java) { answer.get() }
        assertThat(failure).hasCauseThat().isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `the last book kept is the answer`() = runTest {
        val answer = resumption(Store { LastBook(BookId("42"), "The Long Road", null) }, service())

        assertThat(answer.isDone).isTrue()
        assertThat(answer.get().mediaItems).hasSize(1)
    }

    @Test
    fun `no book kept, or no profile to play for, is answered with no book`() = runTest {
        assertNoBook(resumption(Store { null }, service()))
        assertNoBook(resumption(null, service()))
    }

    @Test
    fun `a last book that cannot be read is answered with no book`() = runTest {
        assertNoBook(resumption(Store { error("The last book's file is damaged") }, service()))
    }

    /** A DataStore whose scope ended cancels whoever reads it, though the reader itself was not cancelled. */
    @Test
    fun `a store that closed with its profile is answered with no book`() = runTest {
        val closed = Store { throw CancellationException("The profile's store closed") }

        assertNoBook(resumption(closed, service()))
    }

    @Test
    fun `a service that ends while the book is read is answered with no book`() = runTest {
        val service = service()
        val answer = resumption(Store { awaitCancellation() }, service)
        assertThat(answer.isDone).isFalse()

        service.cancel()

        assertNoBook(answer)
    }

    @Test
    fun `a service that had ended before the book could be read is answered with no book`() = runTest {
        val service = service().also { it.cancel() }

        assertNoBook(resumption(Store { LastBook(BookId("42"), "The Long Road", null) }, service))
    }
}
