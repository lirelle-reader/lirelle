package app.tfdn.lirelle.core.reader.di

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.marks.DataStoreMarks
import app.tfdn.lirelle.core.reader.marks.MarkSamples
import app.tfdn.lirelle.core.reader.marks.MemoryDataStore
import app.tfdn.lirelle.core.reader.marks.forSource
import app.tfdn.lirelle.core.reader.sync.DataStoreOutbox
import app.tfdn.lirelle.core.reader.sync.DataStores
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.PendingSession
import app.tfdn.lirelle.core.reader.sync.forSource
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** The profile's reading queues follow what its source can do (spec 2c). */
class ReaderQueuesCapabilitiesTest {

    @get:Rule
    val dataStores = DataStores()

    private val file = FileId("12")
    private val at = Instant.parse("2026-11-22T10:00:00Z")
    private val position = LocalPosition(file, "epubcfi(/6/4!/4/2/1:0)", 12.5, at)
    private val session = ReadingSessionReport("s-1", at, at.plusSeconds(600), 540, 3.5, 16.0)

    @Test
    fun `without reading sessions no session is queued, and the position still is`() = runBlocking<Unit> {
        val outbox = ReadingProfileModule.outbox(dataStores.create(), KomgaLikeCapabilities)

        outbox.enqueuePosition(position)
        outbox.enqueueSession(file, session)

        assertThat(outbox.pending().sessions).isEmpty()
        assertThat(outbox.pending().positions).containsExactly(position)
    }

    @Test
    fun `with reading sessions the session is queued`() = runBlocking<Unit> {
        val outbox = ReadingProfileModule.outbox(dataStores.create(), SourceCapabilities.Everything)

        outbox.enqueueSession(file, session)

        assertThat(outbox.pending().sessions).containsExactly(PendingSession(file, session))
    }

    @Test
    fun `without annotations no change of the marks is queued nor shown`() = runBlocking<Unit> {
        val marks = DataStoreMarks(MemoryDataStore(), MarkSamples.Wall)
        val outbox = MarksModule.markOutbox(marks, KomgaLikeCapabilities)

        outbox.record(MarkSamples.addHighlight())
        outbox.record(MarkSamples.addBookmark())
        outbox.enqueue(MarkSamples.change())

        assertThat(outbox.pending()).isEmpty()
        assertThat(marks.held(MarkSamples.Book).highlights).isEmpty()
        assertThat(marks.held(MarkSamples.Book).bookmarks).isEmpty()
    }

    @Test
    fun `with annotations a change of the marks is queued`() = runBlocking<Unit> {
        val marks = DataStoreMarks(MemoryDataStore(), MarkSamples.Wall)
        val outbox = MarksModule.markOutbox(marks, SourceCapabilities.Everything)

        outbox.record(MarkSamples.addHighlight())

        assertThat(outbox.pending()).containsExactly(MarkSamples.addHighlight())
    }

    @Test
    fun `a source that can do everything gets the queues themselves`() {
        val outbox = DataStoreOutbox(dataStores.create())
        val marks = DataStoreMarks(MemoryDataStore(), MarkSamples.Wall)

        assertThat(outbox.forSource(SourceCapabilities.Everything)).isSameInstanceAs(outbox)
        assertThat(marks.forSource(SourceCapabilities.Everything)).isSameInstanceAs(marks)
    }
}
