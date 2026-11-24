package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.LocalBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Order across items, writes that fail, bookmarks. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarksFlusherEdgeTest {

    private val rig = FlusherRig()
    private val repository = rig.repository
    private val marks = rig.marks
    private val h5 = MarkId.ofHighlight(sid(5))
    private val h6 = MarkId.ofHighlight(sid(6))

    @Test
    fun `a change to be tried again holds back the changes of other items after it`() = runTest {
        rig.onServer(5, 6)
        repository.failure = ApiError.ServerFailed
        marks.record(delete(h5))
        marks.record(delete(h6))

        rig.flusher(this).kick()
        pass(rig, 600.seconds)

        assertThat(repository.calls.toSet()).containsExactly("deleteHighlight $Book 5")
        assertThat(marks.pending()).containsExactly(delete(h5), delete(h6)).inOrder()
    }

    @Test
    fun `a write that fails ends the pass, which is tried again after a wait instead of spinning`() = runTest {
        rig.onServer(5)
        rig.outbox = object : MarkOutbox by marks {
            override suspend fun markSent(change: MarkChange) = Unit
        }
        marks.record(delete(h5))

        rig.flusher(this).kick()
        runCurrent()
        assertThat(repository.calls).hasSize(1)

        pass(rig, 5.seconds)
        assertThat(repository.calls).hasSize(2)
    }

    @Test
    fun `unclear failures of the listing do not count against the add`() = runTest {
        val adds = ArrayDeque(listOf(ApiError.ServerUnreachable, ApiError.Unexpected))
        val listings = ArrayDeque(listOf(ApiError.Unexpected, ApiError.Unexpected))
        rig.server = object : MarksRepository by repository {
            override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): ApiResult<ServerHighlight> =
                adds.removeFirstOrNull()?.let { ApiResult.Failure(it) } ?: repository.addHighlight(bookId, highlight)

            override suspend fun highlights(bookId: BookId, fileId: FileId): ApiResult<List<ServerHighlight>> =
                listings.removeFirstOrNull()?.let { ApiResult.Failure(it) } ?: repository.highlights(bookId, fileId)
        }
        marks.record(addHighlight())
        rig.flusher(this).kick()
        runCurrent()

        // Listing refused at 5 s and 15 s, empty at 35 s; the add refused once then.
        pass(rig, 35.seconds)
        assertThat(adds).isEmpty()
        assertThat(marks.pending()).hasSize(1)

        pass(rig, 40.seconds)
        assertThat(repository.highlightList).hasSize(1)
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a bookmark deleted here whose add was given up but landed is deleted on the server`() = runTest {
        marks.apply(addBookmark())
        repository.addBookmark(Book, ReadFile, addBookmark().cfi, addBookmark().title)
        marks.record(deleteBookmark())

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls.last()).isEqualTo("deleteBookmark $Book 1")
        assertThat(repository.bookmarkList).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `the same place bookmarked twice here is one bookmark on the server and one shown`() = runTest {
        val again = MarkId("9a4d2c1e-5b3f-4e7a-8c6d-1f2e3d4c5b6a")
        marks.record(addBookmark())
        marks.record(addBookmark(id = again))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls.filter { it.startsWith("addBookmark") }).hasSize(2)
        assertThat(repository.bookmarkList).hasSize(1)
        assertThat(rig.shown().bookmarks.map { it.id to it.serverId }).containsExactly(again to sid(1))
        assertThat(rig.shown().bookmarks.none { it.id == LocalBookmark }).isTrue()
        assertThat(marks.pending()).isEmpty()
    }
}
