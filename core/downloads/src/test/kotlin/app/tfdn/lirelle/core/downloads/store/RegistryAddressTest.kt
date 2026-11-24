package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The registry keeps what each file is, and not where a server serves it: the address is asked when the
 * download runs, so that a record means the same whichever server, or source, the profile is of.
 */
class RegistryAddressTest {

    @get:Rule
    val dataStores = DataStores()

    private val asked = Instant.parse("2026-11-01T10:00:00Z")

    private val epub = PlannedFile("31.epub", FileSource.Audioless(FileId("31")), "v1", null, false, FileKind.Epub)
    private val track = PlannedFile(
        "000-audio_1.mp3",
        FileSource.ReadAlongTrack(FileId("31"), "audio/1.mp3"),
        "31:v1",
        500,
        true,
        FileKind.Audio,
    )
    private val asset =
        PlannedFile("0-aud_1", FileSource.AudioAsset("aud_1"), "e1", 900, true, FileKind.Audio, "aud_1:e1")
    private val pdf = PlannedFile("14.pdf", FileSource.Whole(FileId("14")), "v2", 700, true, FileKind.Pdf)

    /** A book with every kind of file: an album's pages, a narration, an audiobook, and an update to a PDF. */
    private val book = DownloadedBook(
        bookId = BookId("3"),
        formats = listOf(
            FormatRecord(
                DownloadFormat.Text,
                FormatState.Complete(3_000),
                pageFiles(FileId("12"), PagedKind.Djvu, 3, "v1"),
                fileId = FileId("12"),
                version = "v1",
                askedAt = asked,
                update = FormatUpdate("v2", listOf(pdf), bytes = 700),
            ),
            FormatRecord(DownloadFormat.Narration, FormatState.Queued, listOf(epub, track), FileId("31"), "v1", asked),
            FormatRecord(DownloadFormat.Audiobook, FormatState.Queued, listOf(asset), null, "e1", asked),
        ),
        downloadedAt = asked,
        lastOpenedAt = null,
    )

    @Test
    fun `the registry's file holds no address of a server`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStoreDownloadRegistry(dataStore).update(book.bookId) { book }

        val written = dataStores.file().readBytes().toString(Charsets.ISO_8859_1)

        assertThat(written).contains("aud_1")
        assertThat(written).doesNotContain("http")
        assertThat(written).doesNotContain("books.example")
    }

    @Test
    fun `what it keeps of each file comes back as it was`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStoreDownloadRegistry(dataStore).update(book.bookId) { book }

        assertThat(DataStoreDownloadRegistry(dataStore).book(book.bookId)).isEqualTo(book)
    }

    @Test
    fun `a format with a file or pages of a source this version does not know is left out`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStoreDownloadRegistry(dataStore).update(book.bookId) { book }
        dataStore.edit {
            val key = stringPreferencesKey("downloads")
            val before = checkNotNull(it[key])
            check(""""type":"AudioAsset"""" in before && """"paged":"Djvu"""" in before)
            it[key] = before.replace(""""type":"AudioAsset"""", """"type":"Hologram"""")
                .replace(""""paged":"Djvu"""", """"paged":"Scroll"""")
        }

        val read = DataStoreDownloadRegistry(dataStore).book(book.bookId)

        assertThat(read?.formats?.map { it.format }).containsExactly(DownloadFormat.Narration)
    }
}
