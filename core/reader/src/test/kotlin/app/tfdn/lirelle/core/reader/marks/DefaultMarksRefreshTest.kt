package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The lists read again: throttled, one at a time per book, and after the book's changes went. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMarksRefreshTest {

    private val rig = FlusherRig()
    private val marks = rig.marks
    private val repository = rig.repository
    private val h5 = MarkId("h5")
    private val lists = listOf("bookmarks $Book $ReadFile", "highlights $Book $ReadFile")

    /** The server's highlights as they were when asked, answered once [gates] lets each call through. */
    private val gates = ArrayDeque<CompletableDeferred<Unit>>()
    private val slow = object : MarksRepository by repository {
        override suspend fun highlights(bookId: BookId, fileId: FileId): ApiResult<List<ServerHighlight>> {
            val answer = repository.highlights(bookId, fileId)
            gates.removeFirstOrNull()?.await()
            return answer
        }
    }

    private fun facade(flusher: MarksFlusher = idleFlusher(), server: MarksRepository = repository) = DefaultMarks(
        outbox = marks,
        store = marks,
        refresher = MarksRefresher(marks, server, rig.clocks.monotonic),
        flusher = flusher,
    )

    private fun idleFlusher() = MarksFlusher(
        marks, marks, repository, CoroutineScope(StandardTestDispatcher()), rig.clocks.wall,
    )

    private suspend fun shown() = marks.book(Book).first()

    @Test
    fun `a refresh merges the server's lists, keeping what waits to be sent`() = runTest {
        rig.onServer(5)
        val facade = facade()
        facade.recolour(Book, h5, "#38BDF8")
        repository.highlightList.clear()
        repository.highlightList += serverHighlight(5, color = "#F472B6")
        repository.highlightList += serverHighlight(6)
        repository.bookmarkList += serverBookmark(7)

        facade.refresh(Book, ReadFile)

        assertThat(shown().highlights.map { it.id to it.color })
            .containsExactly(h5 to "#38BDF8", MarkId("h6") to "#4ADE80")
        assertThat(shown().bookmarks.map { it.id }).containsExactly(MarkId("b7"))
    }

    @Test
    fun `a refresh comes at most once every 15 seconds per book, unless forced`() = runTest {
        val facade = facade()
        facade.refresh(Book, ReadFile)
        assertThat(repository.calls).containsExactlyElementsIn(lists).inOrder()

        rig.clocks.advance(14.seconds)
        facade.refresh(Book, ReadFile)
        facade.refresh(OtherBook, ReadFile)
        assertThat(repository.calls).hasSize(4)

        facade.refresh(Book, ReadFile, force = true)
        assertThat(repository.calls).hasSize(6)

        rig.clocks.advance(14.seconds)
        facade.refresh(Book, ReadFile)
        assertThat(repository.calls).hasSize(6)
        rig.clocks.advance(1.seconds)
        facade.refresh(Book, ReadFile)
        assertThat(repository.calls).hasSize(8)
    }

    @Test
    fun `a failed refresh keeps what is stored, and the next one is not held back`() = runTest {
        rig.onServer(5)
        val facade = facade()
        repository.failure = ApiError.ServerUnreachable

        facade.refresh(Book, ReadFile)
        assertThat(shown().highlights.map { it.id }).containsExactly(h5)

        repository.failure = null
        repository.highlightList.clear()
        facade.refresh(Book, ReadFile)
        assertThat(shown().highlights).isEmpty()
    }

    @Test
    fun `an older answer arriving late does not overwrite a newer one`() = runTest {
        val facade = facade(server = slow)
        repository.highlightList += serverHighlight(5)
        val late = CompletableDeferred<Unit>()
        gates += late
        launch { facade.refresh(Book, ReadFile) }
        runCurrent()

        repository.highlightList.clear()
        repository.highlightList += serverHighlight(6)
        launch { facade.refresh(Book, ReadFile, force = true) }
        runCurrent()
        assertThat(repository.calls).containsExactlyElementsIn(lists)

        late.complete(Unit)
        runCurrent()
        assertThat(repository.calls).containsExactlyElementsIn(lists + lists).inOrder()
        assertThat(shown().highlights.map { it.id }).containsExactly(MarkId("h6"))
    }

    @Test
    fun `once the book's changes went, its lists are read again at once`() = runTest {
        rig.onServer(5)
        val refresher = MarksRefresher(marks, repository, rig.clocks.monotonic)
        val flusher = MarksFlusher(marks, marks, repository, backgroundScope, rig.clocks.wall, rig.policy, refresher)
        val facade = DefaultMarks(marks, marks, refresher, flusher)
        facade.refresh(Book, ReadFile)

        facade.recolour(Book, h5, "#38BDF8")
        runCurrent()

        assertThat(repository.calls).containsExactlyElementsIn(
            lists + "changeHighlight $Book 5 color=#38BDF8 note=null clearNote=false" + lists,
        ).inOrder()
        assertThat(shown().highlights.single().color).isEqualTo("#38BDF8")
    }
}
