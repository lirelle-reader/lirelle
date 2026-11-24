package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import app.tfdn.lirelle.core.reader.sync.TestClocks
import app.tfdn.lirelle.core.testing.FakeMarksRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * A [MarksFlusher] over a real store and outbox kept in memory, a fake server and clocks moved by
 * hand, so that virtual time drives everything.
 */
internal class FlusherRig {
    val clocks = TestClocks()
    val marks = DataStoreMarks(MemoryDataStore(), clocks.wall)
    val repository = FakeMarksRepository().apply { now = clocks.now }
    val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)

    /** The books the flusher said it sent changes of, in order. */
    val sentBooks = mutableListOf<BookId>()

    /** The outbox the flusher uses: [marks] unless a test wraps it. */
    var outbox: MarkOutbox = marks

    /** The server the flusher talks to: [repository] unless a test wraps it. */
    var server: MarksRepository = repository

    fun flusher(scope: TestScope) =
        MarksFlusher(outbox, marks, server, scope.backgroundScope, clocks.wall, policy) {
            sentBooks += it
        }

    suspend fun shown(bookId: BookId = Book) = marks.book(bookId).first()

    /** Server highlights [ids], on the server and in the store as a refresh brings them (`h<id>`). */
    suspend fun onServer(vararg ids: Long) {
        val highlights = ids.map { serverHighlight(it) }
        repository.highlightList += highlights
        marks.replaceFromServer(Book, emptyList(), highlights, ReadFile)
    }
}

/** Moves virtual time and the rig's wall clock together, then runs what is due. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.pass(rig: FlusherRig, time: Duration) {
    rig.clocks.advance(time)
    advanceTimeBy(time)
    runCurrent()
}
