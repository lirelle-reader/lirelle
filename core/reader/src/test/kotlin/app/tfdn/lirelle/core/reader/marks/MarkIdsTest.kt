package app.tfdn.lirelle.core.reader.marks

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The server may name bookmarks and highlights apart: the same id is two items here. */
class MarkIdsTest {

    private val dataStore = MemoryDataStore()
    private val marks = DataStoreMarks(dataStore, Wall)
    private val b5 = MarkId.ofBookmark(sid(5))
    private val h5 = MarkId.ofHighlight(sid(5))

    private suspend fun shown() = marks.book(Book).first()

    private suspend fun refresh(title: String = "Bookmark 5", color: String = "#4ADE80") = marks.replaceFromServer(
        Book,
        listOf(serverBookmark(5, title = title)),
        listOf(serverHighlight(5, color = color)),
    )

    @Test
    fun `bookmark 5 and highlight 5 of the server are b5 and h5`() = runBlocking<Unit> {
        refresh()

        assertThat(b5).isEqualTo(MarkId("b5"))
        assertThat(h5).isEqualTo(MarkId("h5"))
        assertThat(shown().bookmarks.map { it.id }).containsExactly(b5)
        assertThat(shown().highlights.map { it.id }).containsExactly(h5)
    }

    @Test
    fun `a change waiting on h5 does not keep b5 from taking the server's state`() = runBlocking<Unit> {
        refresh()
        marks.record(change(h5, color = "#38BDF8"))

        refresh(title = "Renamed", color = "#F472B6")

        assertThat(shown().bookmarks.map { it.title }).containsExactly("Renamed")
        assertThat(shown().highlights.map { it.color }).containsExactly("#38BDF8")
    }

    @Test
    fun `a delete waiting on b5 does not keep h5 from taking the server's state`() = runBlocking<Unit> {
        refresh()
        marks.record(deleteBookmark(b5))

        refresh(color = "#F472B6")

        assertThat(shown().bookmarks).isEmpty()
        assertThat(shown().highlights.map { it.color }).containsExactly("#F472B6")
    }

    @Test
    fun `what the first format left is never read`() = runBlocking<Unit> {
        val first = stringPreferencesKey("marks")
        dataStore.edit {
            it[first] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "books":[{"bookId":42,"highlights":[]}],
                "changes":[{"type":"deleteHighlight","bookId":42,"id":"s5"}]}"""
        }

        assertThat(marks.pending()).isEmpty()
        assertThat(shown()).isEqualTo(BookMarks())
    }
}
