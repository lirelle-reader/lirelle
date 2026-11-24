package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.engine.testLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

private val Downloads = stringPreferencesKey("downloads")

private val TheBook = BookId("3")

private const val Pages = 48

/** A text made of pages is written as their count, and comes back as the files it was. */
class StoredPagesTest {

    @get:Rule
    val dataStores = DataStores()

    private val at = Instant.parse("2026-11-01T10:00:00Z")

    private fun registry(dataStore: DataStore<Preferences>) = DataStoreDownloadRegistry(dataStore)

    private suspend fun DataStore<Preferences>.written(): String = checkNotNull(data.first()[Downloads])

    /** The pages of the comic 12 in [version]. */
    private fun comic(count: Int, version: String) = pageFiles(FileId("12"), PagedKind.Comic, count, version)

    private fun album(state: FormatState = FormatState.Downloading(0, null), kept: Int = 12) = FormatRecord(
        format = DownloadFormat.Text,
        state = state,
        files = comic(Pages, "v1"),
        fileId = FileId("12"),
        version = "v1",
        askedAt = at,
        expectedBytes = 5_000,
        textName = TextName.Cbz,
        keptPages = kept,
    )

    @Test
    fun `the pages of a file are named by their index and fetched where the source serves them`() {
        val files = pageFiles(FileId("31"), PagedKind.Djvu, 3, "v4")
        val locations = testLocations("https://books.example")

        assertThat(files.map { it.name }).containsExactly("0", "1", "2").inOrder()
        assertThat(files.map { locations.url(TheBook, it.source) }).containsExactly(
            "https://books.example/pages/djvu/31/0",
            "https://books.example/pages/djvu/31/1",
            "https://books.example/pages/djvu/31/2",
        ).inOrder()
        val last = FileSource.Page(FileId("31"), PagedKind.Djvu, 2)
        assertThat(files.last()).isEqualTo(PlannedFile("2", last, "v4", null, resumable = false, kind = FileKind.Page))
    }

    @Test
    fun `an album is written as its count, with no entry per page, and comes back as it was`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        registry(dataStore).update(TheBook) { DownloadedBook(TheBook, listOf(album()), at, null) }

        val written = dataStore.written()
        val read = registry(dataStore).book(TheBook)?.formats?.single()

        assertThat(written)
            .contains(""""textName":"Cbz","pages":{"count":48,"fileId":"12","paged":"Comic"},"keptPages":12""")
        assertThat(written).doesNotContain(""""files"""")
        assertThat(written).doesNotContain(""""kind"""")
        assertThat(written).doesNotContain("pages/0")
        assertThat(read).isEqualTo(album())
        assertThat(read?.files).hasSize(Pages)
        assertThat(read?.pages).isEqualTo(PageCount(kept = 12, total = Pages))
    }

    @Test
    fun `an update made of pages is written as its count too`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val update = FormatUpdate("v2", comic(50, "v2"), bytes = 6_000, FormatState.Queued, keptPages = 7)
        val kept = album(FormatState.Complete(5_000), kept = Pages).copy(update = update)
        registry(dataStore).update(TheBook) { DownloadedBook(TheBook, listOf(kept), at, null) }

        val written = dataStore.written()
        val read = registry(dataStore).book(TheBook)?.formats?.single()

        assertThat(written).doesNotContain(""""files"""")
        assertThat(written).contains(""""pages":{"count":50,""")
        assertThat(read).isEqualTo(kept)
        assertThat(read?.update?.pages).isEqualTo(PageCount(kept = 7, total = 50))
    }

    @Test
    fun `files that are not exactly the pages of a count are written one by one`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val sized = comic(3, "v1").map { it.copy(expectedBytes = 100) }
        val other = comic(3, "v0")
        val records = listOf(album().copy(files = sized), album().copy(files = other))
        val books = listOf(BookId("1"), BookId("2"))
        books.zip(records).forEach { (book, record) ->
            registry(dataStore).update(book) { DownloadedBook(book, listOf(record), at, null) }
        }

        val read = books.map { registry(dataStore).book(it)?.formats?.single() }

        assertThat(dataStore.written()).doesNotContain(""""pages":""")
        assertThat(read).isEqualTo(records)
    }

    @Test
    fun `a text name written by a later version is the text, and a count below zero is no page`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            registry(dataStore).update(TheBook) { DownloadedBook(TheBook, listOf(album()), at, null) }
            dataStore.edit {
                it[Downloads] = checkNotNull(it[Downloads])
                    .replace(""""textName":"Cbz"""", """"textName":"Later"""")
                    .replace(""""count":48""", """"count":-1""")
            }

            val read = registry(dataStore).book(TheBook)?.formats?.single()

            assertThat(read?.textName).isEqualTo(TextName.Text)
            assertThat(read?.files).isEmpty()
            assertThat(read?.pages).isNull()
        }

    @Test
    fun `a format that is whole has all its pages, whatever was last said of them`() {
        val whole = album(FormatState.Complete(5_000), kept = 3)
        val pdf = PlannedFile("12.pdf", FileSource.Whole(FileId("12")), "v1", 10, true, FileKind.Pdf)
        val text = whole.copy(files = listOf(pdf))

        assertThat(whole.pages).isEqualTo(PageCount(kept = Pages, total = Pages))
        assertThat(album(kept = 99).pages).isEqualTo(PageCount(kept = Pages, total = Pages))
        assertThat(album(kept = 0).pages).isEqualTo(PageCount(kept = 0, total = Pages))
        assertThat(text.pages).isNull()
    }
}
