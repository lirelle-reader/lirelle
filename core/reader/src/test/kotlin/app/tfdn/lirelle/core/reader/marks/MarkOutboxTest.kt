package app.tfdn.lirelle.core.reader.marks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.reader.marks.MarkSamples.At
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.sync.BrokenDataStore
import app.tfdn.lirelle.core.reader.sync.DataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** What the outbox keeps. */
class MarkOutboxTest {

    @get:Rule
    val dataStores = DataStores()

    private fun outbox(dataStore: DataStore<Preferences>): MarkOutbox = DataStoreMarks(dataStore, Wall)

    @Test
    fun `a new outbox is empty`() = runBlocking<Unit> {
        assertThat(outbox(dataStores.create()).pending()).isEmpty()
    }

    @Test
    fun `every kind of change survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val changes = listOf(
            addHighlight(highlight = NewOne.copy(note = "typed", chapterTitle = null)).copy(attemptedAt = At),
            addHighlight(MarkId("other"), highlight = NewOne.copy(cfi = "epubcfi(/6/2!/4/2/1:0)")),
            change(MarkId("h1"), color = "#38BDF8", note = NoteEdit.Set("typed")),
            change(MarkId("h2"), note = NoteEdit.Clear),
            change(MarkId("h3"), color = "#38BDF8"),
            delete(MarkId("h4")),
            addBookmark(),
            deleteBookmark(MarkId("b9")),
        )
        outbox(dataStore).run { changes.forEach { enqueue(it) } }
        outbox(dataStore).run {
            enqueue(delete(MarkId("h7")))
            markSent(delete(MarkId("h7")))
            enqueue(restore(MarkId("h7")))
        }

        assertThat(outbox(dataStore).pending()).containsExactlyElementsIn(changes + restore(MarkId("h7"))).inOrder()
    }

    @Test
    fun `enqueues made at the same time are all kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())

        (1..Writers)
            .map { id -> async(Dispatchers.Default) { outbox.enqueue(delete(MarkId("h$id"))) } }
            .awaitAll()

        assertThat(outbox.pending().map { it.id.value }).containsExactlyElementsIn((1..Writers).map { "h$it" })
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("marks2")] = "{\"changes\": [{\"type\": \"unknown\"}]}" }
        val outbox = outbox(dataStore)

        assertThat(outbox.pending()).isEmpty()

        outbox.enqueue(addHighlight())
        assertThat(outbox.pending()).containsExactly(addHighlight())
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val outbox = outbox(BrokenDataStore)

        outbox.enqueue(addHighlight())
        outbox.replace(addHighlight(), addHighlight().copy(attemptedAt = At))
        outbox.markSent(addHighlight())

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `changes stored with an owner field still read, and are written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("marks2")
        outbox(dataStore).run {
            enqueue(addHighlight().copy(attemptedAt = At))
            enqueue(deleteBookmark(MarkId("b9")))
        }
        // As the released version wrote it: the same value, its owner first.
        val owner = """{"owner":{"serverUrl":"https://books.example","userId":7},"""
        dataStore.edit { it[key] = owner + it[key]!!.removePrefix("{") }
        val outbox = outbox(dataStore)

        assertThat(outbox.pending())
            .containsExactly(addHighlight().copy(attemptedAt = At), deleteBookmark(MarkId("b9"))).inOrder()

        outbox.enqueue(delete(MarkId("h4")))
        assertThat(outbox.pending()).hasSize(3)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    private companion object {
        const val Writers = 20
    }
}
