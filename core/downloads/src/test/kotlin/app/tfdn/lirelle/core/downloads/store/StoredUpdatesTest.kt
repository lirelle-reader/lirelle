package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

private val TheBook = BookId("3")
private val OtherBook = BookId("4")

/**
 * What sequence 4a wrote for one book, its files' addresses now in the shape that names none and the book
 * named by the text of its id: a complete text and an audiobook that waits.
 */
private const val StoredBy4a = """{"owner":{"serverUrl":"https://books.example","userId":7},"books":[{"bookId":"3",
"formats":[{"format":"Text","state":{"name":"Complete","receivedBytes":900},"files":[{"name":"12.epub",
"source":{"type":"Whole","fileId":"12"},"version":"v1","expectedBytes":900,"resumable":true,"kind":"Epub"}],
"fileId":"12","version":"v1","askedAt":"2026-11-01T10:00:00.123Z","expectedBytes":900},
{"format":"Audiobook","state":{"name":"Waiting","receivedBytes":20,"totalBytes":200,"wait":"UnmeteredOnly"},
"files":[{"name":"0-aud_1","source":{"type":"AudioAsset","assetId":"aud_1"},"version":"e1","expectedBytes":200,
"resumable":true,"kind":"Audio","cacheKey":"aud_1:e1"}],"version":"e1","askedAt":"2026-11-01T10:00:00.123Z",
"anyNetwork":true}],"downloadedAt":"2026-11-02T10:00:00Z","lastOpenedAt":"2026-11-03T10:00:00Z"}]}"""

class StoredUpdatesTest {

    @get:Rule
    val dataStores = DataStores()

    private val asked = Instant.parse("2026-11-01T10:00:00.123Z")
    private val downloaded = Instant.parse("2026-11-02T10:00:00Z")
    private val epub = PlannedFile("12.epub", FileSource.Whole(FileId("12")), "v1", 900, true, FileKind.Epub)
    private val newEpub = epub.copy(version = "v2", expectedBytes = 950)

    private fun registry(dataStore: DataStore<Preferences>) = DataStoreDownloadRegistry(dataStore)

    private fun text(update: FormatUpdate?) = FormatRecord(
        DownloadFormat.Text,
        FormatState.Complete(900),
        listOf(epub),
        fileId = FileId("12"),
        version = "v1",
        askedAt = asked,
        update = update,
    )

    private fun book(vararg formats: FormatRecord) = DownloadedBook(TheBook, formats.toList(), downloaded, null)

    @Test
    fun `what 4a stored still reads, with no update, not gone and not to delete`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("downloads")] = StoredBy4a.replace("\n", "") }
        val audio = PlannedFile("0-aud_1", FileSource.AudioAsset("aud_1"), "e1", 200, true, FileKind.Audio, "aud_1:e1")

        val read = registry(dataStore).book(TheBook)

        assertThat(read).isEqualTo(
            DownloadedBook(
                bookId = TheBook,
                formats = listOf(
                    text(update = null).copy(expectedBytes = 900),
                    FormatRecord(
                        format = DownloadFormat.Audiobook,
                        state = FormatState.Waiting(WaitReason.UnmeteredOnly, 20, 200),
                        files = listOf(audio),
                        fileId = null,
                        version = "e1",
                        askedAt = asked,
                        anyNetwork = true,
                    ),
                ),
                downloadedAt = downloaded,
                lastOpenedAt = Instant.parse("2026-11-03T10:00:00Z"),
            ),
        )
        assertThat(read?.gone).isFalse()
        assertThat(read?.deleteAtClose).isFalse()
        assertThat(read?.formats?.map { it.update }).containsExactly(null, null)
    }

    @Test
    fun `an update that waits for the user comes back whole, with its files`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val kept = book(text(FormatUpdate("v2", listOf(newEpub), bytes = 950)))

        registry(dataStore).update(TheBook) { kept }

        assertThat(registry(dataStore).book(TheBook)).isEqualTo(kept)
    }

    @Test
    fun `an update comes back with the name of its text, the text's too, and with none when it had none`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            listOf(TextName.Cbr, TextName.Text, null).forEach { name ->
                val kept = book(text(FormatUpdate("v2", listOf(newEpub), bytes = 950, textName = name)))

                registry(dataStore).update(TheBook) { kept }

                assertThat(registry(dataStore).book(TheBook)).isEqualTo(kept)
            }
        }

    @Test
    fun `an update the user let use any network still may after a restart`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val update = FormatUpdate("v2", listOf(newEpub), bytes = 950, state = FormatState.Queued, anyNetwork = true)
        val kept = book(text(update))

        registry(dataStore).update(TheBook) { kept }

        assertThat(registry(dataStore).book(TheBook)).isEqualTo(kept)
    }

    @Test
    fun `an update asked comes back in the state it was in`() = runBlocking<Unit> {
        val states = listOf(
            FormatState.Queued,
            FormatState.Downloading(10, 950),
            FormatState.Waiting(WaitReason.NoNetwork, 20, null),
            FormatState.Failed(DownloadFailure.NoSpace(4_096), 30),
            FormatState.Complete(950),
        )
        val dataStore = dataStores.create()

        states.forEach { state ->
            val kept = book(text(FormatUpdate(version = null, files = listOf(newEpub), bytes = 950, state = state)))
            registry(dataStore).update(TheBook) { kept }

            assertThat(registry(dataStore).book(TheBook)).isEqualTo(kept)
        }
    }

    @Test
    fun `a vanished book and a deletion at close are still so after a restart`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val registry = registry(dataStore)
        registry.update(TheBook) { book(text(update = null)).copy(gone = true) }
        registry.update(OtherBook) { book(text(update = null)).copy(bookId = OtherBook, deleteAtClose = true) }

        val again = registry(dataStore)

        assertThat(again.book(TheBook)?.gone).isTrue()
        assertThat(again.book(TheBook)?.deleteAtClose).isFalse()
        assertThat(again.book(OtherBook)?.gone).isFalse()
        assertThat(again.book(OtherBook)?.deleteAtClose).isTrue()
    }
}
