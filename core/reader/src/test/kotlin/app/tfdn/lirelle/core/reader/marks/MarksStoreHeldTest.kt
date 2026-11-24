package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Local
import app.tfdn.lirelle.core.reader.marks.MarkSamples.LocalBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** What the store holds of a book for the flusher and an undo: deleted items and the file included. */
class MarksStoreHeldTest {

    private val store = DataStoreMarks(MemoryDataStore(), Wall)

    @Test
    fun `deleted items are held with their server ids`() = runBlocking<Unit> {
        store.record(addHighlight())
        store.bindServerId(Book, Local, sid(31))
        store.record(addBookmark())
        store.bindServerId(Book, LocalBookmark, sid(32))

        store.record(delete())
        store.record(deleteBookmark())

        val held = store.held(Book)
        assertThat(held.highlights.map { it.id to it.serverId }).containsExactly(Local to sid(31))
        assertThat(held.bookmarks.map { Triple(it.id, it.serverId, it.title) })
            .containsExactly(Triple(LocalBookmark, sid(32), "Chapter two"))
    }

    @Test
    fun `the file is the one of the last refresh, or of a mark made here`() = runBlocking<Unit> {
        assertThat(store.held(Book).fileId).isNull()

        store.record(addHighlight())
        assertThat(store.held(Book).fileId).isEqualTo(ReadFile)

        store.replaceFromServer(Book, listOf(serverBookmark(1)), listOf(serverHighlight(2)), fileId = FileId("77"))
        assertThat(store.held(Book).fileId).isEqualTo(FileId("77"))

        store.replaceFromServer(Book, emptyList(), emptyList())
        assertThat(store.held(Book).fileId).isEqualTo(FileId("77"))
        assertThat(store.held(OtherBook).fileId).isNull()
    }
}
