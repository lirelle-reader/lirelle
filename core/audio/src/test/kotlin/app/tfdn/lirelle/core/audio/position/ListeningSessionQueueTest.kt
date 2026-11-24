package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ListeningSessionQueueTest {

    @get:Rule
    val dataStores = DataStores()

    private fun queue(dataStore: DataStore<Preferences> = dataStores.create()) =
        DataStoreListeningSessionQueue(dataStore)

    @Test
    fun `a session waits with its file, and survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        queue(dataStore).enqueue(FileId("50"), sampleSession())

        assertThat(queue(dataStore).pending()).containsExactly(QueuedSession(FileId("50"), sampleSession()))
    }

    @Test
    fun `the same session again replaces the one waiting`() = runBlocking<Unit> {
        val queue = queue()
        queue.enqueue(FileId("50"), sampleSession(seconds = 30))
        queue.enqueue(FileId("51"), sampleSession(id = "other"))
        queue.enqueue(FileId("50"), sampleSession(seconds = 90))

        assertThat(queue.pending().map { it.report.sessionId to it.report.activeSeconds })
            .containsExactly("other" to 20, "s-1" to 90)
            .inOrder()
    }

    @Test
    fun `what was sent leaves, and a longer report of it enqueued meanwhile stays`() = runBlocking<Unit> {
        val queue = queue()
        queue.enqueue(FileId("50"), sampleSession(seconds = 30))
        val sent = queue.pending().single()
        queue.enqueue(FileId("50"), sampleSession(seconds = 60))

        queue.markSent(sent)

        assertThat(queue.pending().single().report.activeSeconds).isEqualTo(60)
        queue.markSent(queue.pending().single())
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("listening_sessions")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "sessions":[{"fileId":"50","sessionId":"s-1","startedAt":"2026-11-15T21:00:00Z",
                "endedAt":"2026-11-15T21:00:20Z","activeSeconds":20,"progressDelta":0.5,"endPercent":12.5}]}"""
        }
        val queue = queue(dataStore)

        assertThat(queue.pending()).containsExactly(QueuedSession(FileId("50"), sampleSession()))

        queue.enqueue(FileId("51"), sampleSession(id = "other"))
        assertThat(queue.pending().map { it.fileId }).containsExactly(FileId("50"), FileId("51")).inOrder()
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }
}

/** A listening sitting of [seconds] active seconds. */
fun sampleSession(id: String = "s-1", seconds: Int = 20) = ReadingSessionReport(
    sessionId = id,
    startedAt = Instant.parse("2026-11-15T21:00:00Z"),
    endedAt = Instant.parse("2026-11-15T21:00:00Z").plusSeconds(seconds.toLong()),
    activeSeconds = seconds,
    progressDelta = 0.5,
    endPercent = 12.5,
)

/** [ListeningSessionQueue] in memory. */
class InMemorySessionQueue : ListeningSessionQueue {
    var sessions: List<QueuedSession> = emptyList()

    override suspend fun enqueue(fileId: FileId, report: ReadingSessionReport) {
        sessions = sessions.filterNot { it.report.sessionId == report.sessionId } + QueuedSession(fileId, report)
    }

    override suspend fun pending() = sessions

    override suspend fun markSent(session: QueuedSession) {
        sessions = sessions - session
    }
}
