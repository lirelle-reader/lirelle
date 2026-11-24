package app.tfdn.lirelle.core.downloads.engine

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DataStoreDownloadRegistry
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A download planned in one process goes on in the next: the registry, read again from its file, says what each
 * file is, and the address it is fetched from is the one the locations give then, not one kept from before.
 */
class ReloadedDownloadTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    @get:Rule
    val folder = TemporaryFolder()

    private val content = bytes(30_000)
    private val asked = Instant.parse("2026-11-01T10:00:00Z")

    /** The registry of one process: its store is closed with it, so that the next reads the file again. */
    private suspend fun <T> inProcess(block: suspend (DataStoreDownloadRegistry) -> T): T {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            folder.root.resolve("downloads.preferences_pb")
        }
        return try {
            block(DataStoreDownloadRegistry(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a planned download reloaded from the store resumes from the address the locations give`() = runBlocking {
        val source = FileSource.AudioAsset("aud_1")
        val planned = PlannedFile("0-aud_1", source, "e1", content.size.toLong(), true, FileKind.Audio)
        val record = FormatRecord(
            DownloadFormat.Audiobook,
            FormatState.Downloading(1_000, content.size.toLong()),
            listOf(planned),
            fileId = null,
            version = "e1",
            askedAt = asked,
        )
        inProcess { registry -> registry.update(TheBook) { DownloadedBook(TheBook, listOf(record), asked, null) } }
        fixture.leavePart(planned, content.copyOf(1_000))
        val served = RangedFile(content)
        fixture.server.dispatcher = served
        val asks = mutableListOf<Pair<BookId, FileSource>>()
        val elsewhere = fixture.server.url("/moved").toString()
        fixture.locations = DownloadLocations { bookId, what ->
            asks += bookId to what
            "$elsewhere/$bookId/${(what as FileSource.AudioAsset).assetId}"
        }

        val reloaded = inProcess { registry -> checkNotNull(registry.book(TheBook)).formats.single().files.single() }
        val result = fixture.fetch(reloaded)

        assertThat(reloaded).isEqualTo(planned)
        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(asks).containsExactly(TheBook to source)
        assertThat(fixture.server.takeRequest().url.encodedPath).isEqualTo("/moved/$TheBook/aud_1")
        assertThat(served.ranges).containsExactly("bytes=1000-")
        assertThat(fixture.target(planned).readBytes()).isEqualTo(content)
    }

    @Test
    fun `with no server to ask, a file waits as for a session that ended, and keeps what it has`() = runBlocking {
        val file = fixture.audio(expectedBytes = content.size.toLong())
        fixture.leavePart(file, content.copyOf(1_000))
        fixture.locations = DownloadLocations { _, _ -> null }

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Cut(1_000, ApiError.SessionExpired))
        assertThat(fixture.server.requestCount).isEqualTo(0)
        assertThat(fixture.part(file).length()).isEqualTo(1_000)
    }
}
