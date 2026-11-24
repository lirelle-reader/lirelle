package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("3")
private val OtherBook = BookId("4")

/**
 * What the app wrote before it kept other texts than an EPUB, its file's address now in the shape that names
 * none and its book named by the text of its id: one book, its text complete.
 */
private val StoredBefore = """{"owner":{"serverUrl":"https://books.example","userId":7},"books":[{"bookId":"3",
"formats":[{"format":"Text","state":{"name":"Complete","receivedBytes":900},"files":[{"name":"12.epub",
"source":{"type":"Whole","fileId":"12"},"version":"v1","expectedBytes":900,"resumable":true,"kind":"Epub"}],
"fileId":"12","version":"v1","askedAt":"2026-11-01T10:00:00Z","expectedBytes":900}],
"downloadedAt":"2026-11-02T10:00:00Z"}]}""".replace("\n", "")

private val Downloads = stringPreferencesKey("downloads")

/** How the kind of a kept text is written, and what was written before the other kinds were known. */
class StoredTextKindsTest {

    @get:Rule
    val dataStores = DataStores()

    @get:Rule
    val folder = TemporaryFolder()

    private val at = Instant.parse("2026-11-01T10:00:00Z")

    private fun text(file: PlannedFile, fileId: FileId) =
        FormatRecord(DownloadFormat.Text, FormatState.Complete(10), listOf(file), fileId, "v1", askedAt = at)

    @Test
    fun `a record written before still reads, and its EPUB is found where it was put`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val registry = DataStoreDownloadRegistry(dataStore)
        dataStore.edit { it[Downloads] = StoredBefore }
        val files = DownloadFiles(File(folder.root, "downloads"))
        val epub = File(files.bookDir(TheBook), "epub/12.epub").apply { checkNotNull(parentFile).mkdirs() }
        epub.writeText("the book")

        val record = checkNotNull(registry.book(TheBook)).formats.single()

        assertThat(record.files.single().kind).isEqualTo(FileKind.Epub)
        assertThat(record.files.single().name).isEqualTo("12.epub")
        val downloaded = RegistryDownloadedFiles(registry, files) { _, _ -> null }
        assertThat(downloaded.text(TheBook, FileId("12"))).isEqualTo(epub)
    }

    @Test
    fun `a PDF and a Kindle book are written with their kinds and names, and come back as they were`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            val registry = DataStoreDownloadRegistry(dataStore)
            val pdf = PlannedFile("12.pdf", FileSource.Whole(FileId("12")), "v1", 10, true, FileKind.Pdf)
            val mobi = PlannedFile("14.mobi", FileSource.Whole(FileId("14")), "v1", 10, true, FileKind.Book)
            registry.update(TheBook) { DownloadedBook(TheBook, listOf(text(pdf, FileId("12"))), at, null) }
            registry.update(OtherBook) { DownloadedBook(OtherBook, listOf(text(mobi, FileId("14"))), at, null) }

            val written = checkNotNull(dataStore.data.first()[Downloads])
            val again = DataStoreDownloadRegistry(dataStore)

            assertThat(written).contains(""""name":"12.pdf"""")
            assertThat(written).contains(""""kind":"Pdf"""")
            assertThat(written).contains(""""kind":"Book"""")
            // Written with no name of its own, the PDF is named by its file.
            assertThat(again.book(TheBook)?.formats?.single())
                .isEqualTo(text(pdf, FileId("12")).copy(textName = TextName.Pdf))
            assertThat(again.book(OtherBook)?.formats?.single()).isEqualTo(text(mobi, FileId("14")))
        }

    @Test
    fun `a kind written by a later version reads as audio, and the record stays`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val registry = DataStoreDownloadRegistry(dataStore)
        val pdf = PlannedFile("12.pdf", FileSource.Whole(FileId("12")), "v1", 10, true, FileKind.Pdf)
        registry.update(TheBook) { DownloadedBook(TheBook, listOf(text(pdf, FileId("12"))), at, null) }
        dataStore.edit { it[Downloads] = checkNotNull(it[Downloads]).replace(""""kind":"Pdf"""", """"kind":"Later"""") }

        val read = DataStoreDownloadRegistry(dataStore).book(TheBook)?.formats?.single()

        // Its name still ends as a PDF's does: it is named as one.
        assertThat(read).isEqualTo(text(pdf.copy(kind = FileKind.Audio), FileId("12")).copy(textName = TextName.Pdf))
    }
}
