package app.tfdn.lirelle.core.reader.marks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.reader.marks.MarkSamples.At
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Local
import app.tfdn.lirelle.core.reader.marks.MarkSamples.LocalBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import app.tfdn.lirelle.core.reader.sync.BrokenDataStore
import app.tfdn.lirelle.core.reader.sync.DataStores
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Local changes, server ids and persistence of the marks store. */
class MarksStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val dataStore by lazy { dataStores.create() }

    private fun store(dataStore: DataStore<Preferences> = this.dataStore): MarksStore =
        DataStoreMarks(dataStore, Clock.fixed(At, ZoneOffset.UTC))

    private suspend fun MarksStore.shown(bookId: BookId = Book) = book(bookId).first()

    private val added = Highlight(
        id = Local, serverId = null, cfi = NewOne.cfi, text = NewOne.text, color = NewOne.color,
        style = HighlightStyle.Highlight, note = null, chapterTitle = NewOne.chapterTitle, chapterIndex = 1,
        origin = MarkOrigin.Web, positionStatus = null, createdAt = At,
    )

    @Test
    fun `a new store shows nothing`() = runBlocking<Unit> {
        assertThat(store().shown()).isEqualTo(BookMarks())
    }

    @Test
    fun `an added highlight shows at once`() = runBlocking<Unit> {
        val store = store()

        store.apply(addHighlight())

        assertThat(store.shown().highlights).containsExactly(added)
        assertThat(store.shown(bookId = OtherBook)).isEqualTo(BookMarks())
    }

    @Test
    fun `a change sets the colour and the note`() = runBlocking<Unit> {
        val store = store()
        store.apply(addHighlight())

        store.apply(change(color = "#38BDF8", note = NoteEdit.Set("typed")))
        assertThat(store.shown().highlights).containsExactly(added.copy(color = "#38BDF8", note = "typed"))

        store.apply(change(note = NoteEdit.Clear))
        assertThat(store.shown().highlights).containsExactly(added.copy(color = "#38BDF8"))
    }

    @Test
    fun `a deleted highlight is hidden, and a restore brings it back as it was`() = runBlocking<Unit> {
        val store = store()
        store.apply(addHighlight())
        store.apply(change(note = NoteEdit.Set("typed")))

        store.apply(delete())
        assertThat(store.shown().highlights).isEmpty()

        store.apply(restore())
        assertThat(store.shown().highlights).containsExactly(added.copy(note = "typed"))
    }

    @Test
    fun `bookmarks are added and deleted`() = runBlocking<Unit> {
        val store = store()

        store.apply(addBookmark())
        assertThat(store.shown().bookmarks).containsExactly(
            Bookmark(LocalBookmark, null, "epubcfi(/6/8!/4/2/1:0)", "Chapter two", null, MarkOrigin.Web, At),
        )

        store.apply(deleteBookmark())
        assertThat(store.shown().bookmarks).isEmpty()
    }

    @Test
    fun `marks are shown in reading order`() = runBlocking<Unit> {
        val store = store()
        val early = MarkId("early")
        store.apply(addHighlight())
        store.apply(addHighlight(early, highlight = NewOne.copy(cfi = "epubcfi(/6/2!/4/2,/1:0,/1:3)")))
        store.apply(addHighlight(MarkId("nowhere"), highlight = NewOne.copy(cfi = "broken")))

        assertThat(store.shown().highlights.map { it.id }).containsExactly(early, Local, MarkId("nowhere")).inOrder()
    }

    @Test
    fun `a server id is bound without changing the item's id`() = runBlocking<Unit> {
        val store = store()
        store.apply(addHighlight())

        store.bindServerId(Book, Local, sid(31))

        assertThat(store.shown().highlights).containsExactly(added.copy(serverId = sid(31)))
    }

    @Test
    fun `binding drops the copy a refresh already brought`() = runBlocking<Unit> {
        val marks = DataStoreMarks(dataStore, Clock.fixed(At, ZoneOffset.UTC))
        val store: MarksStore = marks
        marks.record(addHighlight())
        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(31)))

        store.bindServerId(Book, Local, sid(31))

        assertThat(store.shown().highlights.map { it.id }).containsExactly(Local)
    }

    @Test
    fun `marks survive a new instance`() = runBlocking<Unit> {
        store().run {
            apply(addHighlight())
            apply(change(note = NoteEdit.Set("typed")))
            apply(addBookmark())
            replaceFromServer(OtherBook, emptyList(), listOf(serverHighlight(3)))
        }

        val store = store()

        assertThat(store.shown().highlights).containsExactly(added.copy(note = "typed"))
        assertThat(store.shown().bookmarks.map { it.id }).containsExactly(LocalBookmark)
        assertThat(store.shown(bookId = OtherBook).highlights.map { it.serverId }).containsExactly(sid(3))
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val key = stringPreferencesKey("marks2")
        store().run {
            apply(addHighlight())
            replaceFromServer(OtherBook, emptyList(), listOf(serverHighlight(3)))
        }
        // As the released version wrote it: the same value, its owner first.
        val owner = """{"owner":{"serverUrl":"https://books.example","userId":7},"""
        dataStore.edit { it[key] = owner + it[key]!!.removePrefix("{") }
        val store = store()

        assertThat(store.shown().highlights).containsExactly(added)
        assertThat(store.shown(bookId = OtherBook).highlights.map { it.serverId }).containsExactly(sid(3))

        store.apply(addBookmark())
        assertThat(store.shown().highlights).containsExactly(added)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        dataStore.edit { it[stringPreferencesKey("marks2")] = "{\"books\": [" }
        val store = store()

        assertThat(store.shown()).isEqualTo(BookMarks())

        store.apply(addHighlight())
        assertThat(store.shown().highlights).containsExactly(added)
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val store = store(BrokenDataStore)

        store.apply(addHighlight())
        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(3)))
        store.bindServerId(Book, Local, sid(3))

        assertThat(store.shown()).isEqualTo(BookMarks())
    }
}
