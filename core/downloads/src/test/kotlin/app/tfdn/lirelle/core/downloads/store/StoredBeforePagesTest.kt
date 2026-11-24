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
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The records as the app wrote them before it kept pages, each file's address now in the shape that names
 * none and each book named by the text of its id (the profiles of before start again empty, but the fields
 * added since stay optional): an EPUB
 * complete, a PDF complete, and an audiobook that waits; with the owner of before the profiles, and no names
 * of texts.
 */
private val StoredBefore = """{"owner":{"serverUrl":"https://books.example","userId":7},"books":[{"bookId":"3",
"formats":[{"format":"Text","state":{"name":"Complete","receivedBytes":900},"files":[{"name":"12.epub",
"source":{"type":"Whole","fileId":"12"},"version":"v1","expectedBytes":900,"resumable":true,
"kind":"Epub"}],"fileId":"12","version":"v1","askedAt":"2026-11-01T10:00:00Z","expectedBytes":900},
{"format":"Audiobook","state":{"name":"Waiting","receivedBytes":20,"totalBytes":200,"wait":"UnmeteredOnly"},
"files":[{"name":"0-aud_1","source":{"type":"AudioAsset","assetId":"aud_1"},"version":"e1","expectedBytes":200,
"resumable":true,"kind":"Audio","cacheKey":"aud_1:e1"}],"version":"e1","askedAt":"2026-11-01T10:00:00Z",
"anyNetwork":true}],"downloadedAt":"2026-11-02T10:00:00Z"},{"bookId":"4",
"formats":[{"format":"Text","state":{"name":"Complete","receivedBytes":700},"files":[{"name":"14.pdf",
"source":{"type":"Whole","fileId":"14"},"version":"v3","expectedBytes":700,"resumable":true,
"kind":"Pdf"}],"fileId":"14","version":"v3","askedAt":"2026-11-01T10:00:00Z","expectedBytes":700}],
"downloadedAt":"2026-11-02T10:00:00Z"}]}""".replace("\n", "")

private val Downloads = stringPreferencesKey("downloads")

/**
 * The records written before a text could be made of pages read as they did, and are written as they were; a
 * PDF, which had no name of its own then, takes it from its file.
 */
class StoredBeforePagesTest {

    @get:Rule
    val dataStores = DataStores()

    private val asked = Instant.parse("2026-11-01T10:00:00Z")

    private fun text(file: PlannedFile, fileId: FileId, bytes: Long) = FormatRecord(
        DownloadFormat.Text,
        FormatState.Complete(bytes),
        listOf(file),
        fileId,
        file.version,
        askedAt = asked,
        expectedBytes = bytes,
    )

    private val epub = PlannedFile("12.epub", FileSource.Whole(FileId("12")), "v1", 900, true, FileKind.Epub)
    private val pdf = PlannedFile("14.pdf", FileSource.Whole(FileId("14")), "v3", 700, true, FileKind.Pdf)
    private val asset = FileSource.AudioAsset("aud_1")
    private val audiobook = FormatRecord(
        DownloadFormat.Audiobook,
        FormatState.Waiting(WaitReason.UnmeteredOnly, 20, 200),
        listOf(PlannedFile("0-aud_1", asset, "e1", 200, true, FileKind.Audio, "aud_1:e1")),
        fileId = null,
        version = "e1",
        askedAt = asked,
        anyNetwork = true,
    )

    @Test
    fun `an EPUB and an audiobook written before read unchanged, named as texts, with no pages`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            dataStore.edit { it[Downloads] = StoredBefore }
            val registry = DataStoreDownloadRegistry(dataStore)

            val formats = checkNotNull(registry.book(BookId("3"))).formats

            assertThat(formats).containsExactly(text(epub, FileId("12"), 900), audiobook).inOrder()
            formats.forEach {
                assertThat(it.textName).isEqualTo(TextName.Text)
                assertThat(it.pages).isNull()
            }
        }

    @Test
    fun `a PDF written before it had a name is named by its file`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[Downloads] = StoredBefore }
        val registry = DataStoreDownloadRegistry(dataStore)

        val kept = checkNotNull(registry.book(BookId("4"))).formats.single()

        assertThat(kept).isEqualTo(text(pdf, FileId("14"), 700).copy(textName = TextName.Pdf))
        assertThat(kept.pages).isNull()
    }

    @Test
    fun `written again, they are word for word what they were, but for the name the PDF now says`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            dataStore.edit { it[Downloads] = StoredBefore }
            val registry = DataStoreDownloadRegistry(dataStore)

            // Each book is read to a record and written back from it.
            registry.update(BookId("3")) { it }
            registry.update(BookId("4")) { it }

            val named = StoredBefore.replace(""""expectedBytes":700}""", """"expectedBytes":700,"textName":"Pdf"}""")
            check(named != StoredBefore)
            // The owner named before the profiles is no longer written: the file is one profile's.
            val unowned = named.replace(""""owner":{"serverUrl":"https://books.example","userId":7},""", "")
            check(unowned != named)
            assertThat(checkNotNull(dataStore.data.first()[Downloads])).isEqualTo(unowned)
            assertThat(registry.books.first().map(DownloadedBook::bookId)).containsExactly(BookId("3"), BookId("4"))
        }
}
