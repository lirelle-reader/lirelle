package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class OutboxTest {

    @get:Rule
    val dataStores = DataStores()

    private val at = Instant.parse("2026-11-14T10:00:00.5Z")
    private val position = LocalPosition(File12, "epubcfi(/6/4!/4/2/1:0)", 12.5, at)
    private val session = ReadingSessionReport("s-1", at, at.plusSeconds(600), 540, 3.5, 16.0)
    private val narration = LocalNarration(File12, NarrationPlace(95.25, "ch1.xhtml#s3", 2), 12.0, at)

    private fun outbox(dataStore: DataStore<Preferences>) = DataStoreOutbox(dataStore)

    @Test
    fun `a new outbox is empty`() = runBlocking<Unit> {
        val content = outbox(dataStores.create()).pending()

        assertThat(content.isEmpty).isTrue()
    }

    @Test
    fun `only the latest position of a file is kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val later = position.copy(cfi = null, percent = 14.0, at = at.plusSeconds(5))
        val other = position.copy(fileId = File13)

        outbox.enqueuePosition(position)
        outbox.enqueuePosition(other)
        outbox.enqueuePosition(later)

        assertThat(outbox.pending().positions).containsExactly(other, later).inOrder()
    }

    @Test
    fun `a session with a known id replaces the stored one`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val longer = session.copy(endedAt = at.plusSeconds(900), activeSeconds = 800)
        val other = session.copy(sessionId = "s-2")

        outbox.enqueueSession(File12, session)
        outbox.enqueueSession(File12, other)
        outbox.enqueueSession(File12, longer)

        assertThat(outbox.pending().sessions)
            .containsExactly(PendingSession(File12, other), PendingSession(File12, longer)).inOrder()
    }

    @Test
    fun `content survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val empty = session.copy(progressDelta = null, endPercent = null)
        outbox(dataStore).run {
            enqueuePosition(position)
            enqueueSession(File12, session)
            enqueueSession(File13, empty.copy(sessionId = "s-2"))
        }

        val content = outbox(dataStore).pending()

        assertThat(content.positions).containsExactly(position)
        assertThat(content.sessions)
            .containsExactly(PendingSession(File12, session), PendingSession(File13, empty.copy(sessionId = "s-2")))
            .inOrder()
    }

    @Test
    fun `what was sent is removed`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        outbox.enqueuePosition(position)
        outbox.enqueueSession(File12, session)

        outbox.markSent(position)
        outbox.markSent(PendingSession(File12, session))

        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a position enqueued while an older one was being sent stays`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val later = position.copy(percent = 14.0, at = at.plusSeconds(5))
        outbox.enqueuePosition(position)
        outbox.enqueuePosition(later)

        outbox.markSent(position)

        assertThat(outbox.pending().positions).containsExactly(later)
    }

    @Test
    fun `a longer report enqueued while the session was being sent stays`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val longer = session.copy(activeSeconds = 800)
        outbox.enqueueSession(File12, session)
        outbox.enqueueSession(File12, longer)

        outbox.markSent(PendingSession(File12, session))

        assertThat(outbox.pending().sessions).containsExactly(PendingSession(File12, longer))
    }

    @Test
    fun `enqueues made at the same time are all kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())

        val files = (1L..Writers).map { FileId("$it") }
        files
            .map { file -> async(Dispatchers.Default) { outbox.enqueuePosition(position.copy(fileId = file)) } }
            .awaitAll()

        assertThat(outbox.pending().positions.map { it.fileId }).containsExactlyElementsIn(files)
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("outbox")] = "{\"positions\": 3" }
        val outbox = outbox(dataStore)

        assertThat(outbox.pending().isEmpty).isTrue()

        outbox.enqueuePosition(position)
        assertThat(outbox.pending().positions).containsExactly(position)
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val outbox = outbox(BrokenDataStore)

        outbox.enqueuePosition(position)
        outbox.enqueueSession(File12, session)
        outbox.markSent(position)

        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("outbox")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},""" +
                """"positions":[{"fileId":"12","percent":3.0,"at":"2026-11-14T10:00:00Z"}],""" +
                """"sessions":[{"fileId":"12","sessionId":"s-0","startedAt":"2026-11-14T10:00:00Z",""" +
                """"endedAt":"2026-11-14T10:10:00Z","activeSeconds":540}],""" +
                """"narrations":[{"fileId":"12","seconds":95.25,"fragment":"ch1.xhtml#s3","sectionIndex":2,""" +
                """"percent":12.0,"at":"2026-11-14T10:00:00.5Z"}]}"""
        }
        val outbox = outbox(dataStore)
        val start = Instant.parse("2026-11-14T10:00:00Z")

        val content = outbox.pending()
        assertThat(content.positions).containsExactly(LocalPosition(File12, null, 3.0, start))
        assertThat(content.sessions).containsExactly(
            PendingSession(File12, ReadingSessionReport("s-0", start, start.plusSeconds(600), 540, null, null)),
        )
        assertThat(content.narrations).containsExactly(narration)

        outbox.enqueueSession(File12, session)
        assertThat(outbox.pending().positions).hasSize(1)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `only the latest narration of a file is kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val later = narration.copy(place = NarrationPlace(99.5, "ch1.xhtml#s4", 2), at = at.plusSeconds(5))
        val other = narration.copy(fileId = File13)

        outbox.enqueueNarration(narration)
        outbox.enqueueNarration(other)
        outbox.enqueueNarration(later)

        assertThat(outbox.pending().narrations).containsExactly(other, later).inOrder()
    }

    @Test
    fun `the latest text position and the latest narration of a file are kept apart`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())

        outbox.enqueuePosition(position)
        outbox.enqueueNarration(narration)

        val content = outbox.pending()
        assertThat(content.positions).containsExactly(position)
        assertThat(content.narrations).containsExactly(narration)
        assertThat(content.isEmpty).isFalse()
    }

    @Test
    fun `an outbox holding only a narration is not empty`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())

        outbox.enqueueNarration(narration)

        assertThat(outbox.pending().isEmpty).isFalse()
    }

    @Test
    fun `each mark as sent removes only its own`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        outbox.enqueuePosition(position)
        outbox.enqueueNarration(narration)

        outbox.markSent(narration)
        assertThat(outbox.pending().positions).containsExactly(position)
        assertThat(outbox.pending().narrations).isEmpty()

        outbox.enqueueNarration(narration)
        outbox.markSent(position)
        assertThat(outbox.pending().positions).isEmpty()
        assertThat(outbox.pending().narrations).containsExactly(narration)
    }

    @Test
    fun `a narration enqueued while an older one was being sent stays`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val later = narration.copy(percent = 14.0, at = at.plusSeconds(5))
        outbox.enqueueNarration(narration)
        outbox.enqueueNarration(later)

        outbox.markSent(narration)

        assertThat(outbox.pending().narrations).containsExactly(later)
    }

    @Test
    fun `narrations survive a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        outbox(dataStore).enqueueNarration(narration)

        assertThat(outbox(dataStore).pending().narrations).containsExactly(narration)
    }

    @Test
    fun `an outbox stored before narrations existed still reads`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit {
            it[stringPreferencesKey("outbox")] = """{"owner":{"serverUrl":"https://books.example","userId":7},""" +
                """"positions":[{"fileId":"12","percent":3.0,"at":"2026-11-14T10:00:00Z"}]}"""
        }

        val content = outbox(dataStore).pending()

        val stored = LocalPosition(File12, null, 3.0, Instant.parse("2026-11-14T10:00:00Z"))
        assertThat(content.positions).containsExactly(stored)
        assertThat(content.narrations).isEmpty()
    }

    private companion object {
        const val Writers = 20L
        val File12 = FileId("12")
        val File13 = FileId("13")
    }
}
