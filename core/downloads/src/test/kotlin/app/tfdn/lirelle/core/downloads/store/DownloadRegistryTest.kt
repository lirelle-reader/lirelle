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
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DownloadRegistryTest {

    @get:Rule
    val dataStores = DataStores()

    private val asked = Instant.parse("2026-11-01T10:00:00.123Z")
    private val downloaded = Instant.parse("2026-11-02T10:00:00Z")
    private fun registry(dataStore: DataStore<Preferences>) = DataStoreDownloadRegistry(dataStore)

    private fun record(state: FormatState, format: DownloadFormat = DownloadFormat.Text) =
        FormatRecord(format, state, files = emptyList(), fileId = null, version = null, askedAt = asked)

    private fun book(id: String, vararg formats: FormatRecord) =
        DownloadedBook(BookId(id), formats.toList(), downloaded, lastOpenedAt = null)

    private val errors = listOf(
        ApiError.ServerUnreachable,
        ApiError.UnknownServer,
        ApiError.InvalidCredentials,
        ApiError.AccountLocked(90),
        ApiError.TooManyAttempts,
        ApiError.DefaultPasswordMustChange,
        ApiError.OidcCancelled,
        ApiError.OidcRefused,
        ApiError.SignInRefused,
        ApiError.UnencryptedSignInPage,
        ApiError.BrowserUnavailable,
        ApiError.SessionExpired,
        ApiError.NotFound,
        ApiError.Moved(BookId("42")),
        ApiError.ServerFailed,
        ApiError.Unexpected,
    )

    private val states = listOf(
        FormatState.Queued,
        FormatState.Downloading(10, 100),
        FormatState.Downloading(10, null),
        FormatState.Waiting(WaitReason.NoNetwork, 20, 200),
        FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null),
        FormatState.Failed(DownloadFailure.NoSpace(4_096), 30),
        FormatState.Failed(DownloadFailure.NotAnEpub, 0),
        FormatState.Failed(DownloadFailure.Damaged, 50),
        FormatState.Failed(DownloadFailure.NotAllowed, 60),
        FormatState.Complete(1_000),
    ) + errors.map { FormatState.Failed(DownloadFailure.Server(it), 7) }

    @Test
    fun `the size a format was asked with is still known after a restart`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val sized = record(FormatState.Queued).copy(expectedBytes = 412)
        val unsized = record(FormatState.Queued, DownloadFormat.Audiobook)

        registry(dataStore).update(BookId("1")) { book("1", sized, unsized) }

        assertThat(registry(dataStore).book(BookId("1"))?.formats).containsExactly(sized, unsized).inOrder()
    }

    @Test
    fun `a format being deleted is still so after a restart`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val going = record(FormatState.Complete(9)).copy(removing = true)
        val audiobook = record(FormatState.Queued, DownloadFormat.Audiobook)
        registry(dataStore).update(BookId("4")) { book("4", going, audiobook) }

        val read = checkNotNull(registry(dataStore).book(BookId("4")))

        assertThat(read.formats.map { it.removing }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `a new registry is empty`() = runBlocking<Unit> {
        val registry = registry(dataStores.create())

        assertThat(registry.books.first()).isEmpty()
        assertThat(registry.book(BookId("1"))).isNull()
    }

    @Test
    fun `every state and failure comes back as it was kept`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val books = states.mapIndexed { index, state -> book("book $index", record(state)) }
        val registry = registry(dataStore)
        books.forEach { book -> registry.update(book.bookId) { book } }

        val again = registry(dataStore)

        assertThat(again.books.first()).containsExactlyElementsIn(books)
        books.forEach { assertThat(again.book(it.bookId)).isEqualTo(it) }
    }

    @Test
    fun `a record comes back whole, with its files, formats and dates`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val file = FileId("12")
        val epub = PlannedFile("12.epub", FileSource.Whole(file), "v3", 2_000, resumable = true, FileKind.Epub)
        val track = PlannedFile("1.mp3", FileSource.ReadAlongTrack(file, "1.mp3"), null, null, false, FileKind.Audio)
        val asset = track.copy(name = "0-aud_1", source = FileSource.AudioAsset("aud_1"), cacheKey = "aud_1:e1")
        val book = DownloadedBook(
            bookId = BookId("5"),
            formats = listOf(
                FormatRecord(DownloadFormat.Text, FormatState.Complete(2_000), listOf(epub), file, "v3", asked),
                FormatRecord(
                    DownloadFormat.Narration, FormatState.Queued, listOf(epub, track), file, "v3", asked, true,
                ),
                FormatRecord(DownloadFormat.Audiobook, FormatState.Queued, listOf(track, asset), null, null, asked),
            ),
            downloadedAt = downloaded,
            lastOpenedAt = Instant.parse("2026-11-03T08:30:00Z"),
        )
        registry(dataStore).update(BookId("5")) { book }

        assertThat(registry(dataStore).book(BookId("5"))).isEqualTo(book)
    }

    @Test
    fun `an update changes what is there, and to null removes it`() = runBlocking<Unit> {
        val registry = registry(dataStores.create())
        registry.update(BookId("1")) { seen ->
            assertThat(seen).isNull()
            book("1", record(FormatState.Queued))
        }
        registry.update(BookId("2")) { book("2", record(FormatState.Queued)) }

        registry.update(BookId("1")) { it?.copy(formats = listOf(record(FormatState.Complete(9)))) }
        assertThat(registry.book(BookId("1"))).isEqualTo(book("1", record(FormatState.Complete(9))))

        registry.update(BookId("1")) { null }
        assertThat(registry.book(BookId("1"))).isNull()
        assertThat(registry.books.first().map { it.bookId }).containsExactly(BookId("2"))
    }

    @Test
    fun `the record is kept under the id asked, whatever the change returns`() = runBlocking<Unit> {
        val registry = registry(dataStores.create())

        registry.update(BookId("1")) { book("99", record(FormatState.Queued)) }

        assertThat(registry.book(BookId("1"))?.bookId).isEqualTo(BookId("1"))
        assertThat(registry.book(BookId("99"))).isNull()
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("downloads")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},"books":[{"bookId":"3",
                "formats":[{"format":"Text","state":{"name":"queued"},
                "askedAt":"2026-11-01T10:00:00.123Z"}],"downloadedAt":"2026-11-02T10:00:00Z"}]}"""
        }
        val registry = registry(dataStore)

        assertThat(registry.books.first().map { it.bookId }).containsExactly(BookId("3"))
        assertThat(registry.book(BookId("3"))?.formats?.single()?.format).isEqualTo(DownloadFormat.Text)

        registry.update(BookId("4")) { book("4", record(FormatState.Queued)) }
        assertThat(registry.books.first().map { it.bookId }).containsExactly(BookId("3"), BookId("4"))
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `clear empties it`() = runBlocking<Unit> {
        val registry = registry(dataStores.create())
        registry.update(BookId("1")) { book("1", record(FormatState.Queued)) }

        registry.clear()

        assertThat(registry.books.first()).isEmpty()
    }

    @Test
    fun `a corrupt value reads as empty`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("downloads")] = "{not json" }

        assertThat(registry(dataStore).books.first()).isEmpty()
    }

    @Test
    fun `a store that cannot be read is empty and an update is dropped`() = runBlocking<Unit> {
        val registry = registry(BrokenDataStore)

        registry.update(BookId("1")) { book("1", record(FormatState.Queued)) }

        assertThat(registry.books.first()).isEmpty()
    }

    @Test
    fun `an unknown server is kept under the name it had when the app knew BookOrbit alone`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("downloads")
        registry(dataStore).update(BookId("1")) {
            book("1", record(FormatState.Failed(DownloadFailure.Server(ApiError.UnknownServer), 3)))
        }
        val written = checkNotNull(dataStore.data.first()[key])

        assertThat(written).contains("\"NotBookOrbit\"")
        assertThat(registry(dataStore).book(BookId("1"))?.formats?.single()?.state)
            .isEqualTo(FormatState.Failed(DownloadFailure.Server(ApiError.UnknownServer), 3))
    }

    @Test
    fun `an unknown stored error reads as unexpected, the rest is kept`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        registry(dataStore).update(BookId("1")) {
            book("1", record(FormatState.Failed(DownloadFailure.Server(ApiError.NotFound), 3)))
        }
        dataStore.edit {
            val key = stringPreferencesKey("downloads")
            it[key] = checkNotNull(it[key]).replace("\"NotFound\"", "\"SomethingNewer\"")
        }

        assertThat(registry(dataStore).book(BookId("1"))?.formats?.single()?.state)
            .isEqualTo(FormatState.Failed(DownloadFailure.Server(ApiError.Unexpected), 3))
    }

    @Test
    fun `a file kept before the cache keys were is read without one`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val asset = PlannedFile("0-aud_1", FileSource.AudioAsset("aud_1"), "e1", 9, true, FileKind.Audio, "aud_1:e1")
        val record = record(FormatState.Queued, DownloadFormat.Audiobook).copy(files = listOf(asset))
        registry(dataStore).update(BookId("1")) { book("1", record) }
        dataStore.edit {
            val key = stringPreferencesKey("downloads")
            val before = checkNotNull(it[key])
            it[key] = before.replace(",\"cacheKey\":\"aud_1:e1\"", "").also { after -> check(after != before) }
        }

        assertThat(registry(dataStore).book(BookId("1"))?.formats?.single()?.files)
            .containsExactly(asset.copy(cacheKey = null))
    }

    @Test
    fun `a format this version does not know is left out, the others are kept`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        registry(dataStore).update(BookId("1")) {
            book("1", record(FormatState.Queued), record(FormatState.Complete(4), DownloadFormat.Audiobook))
        }
        dataStore.edit {
            val key = stringPreferencesKey("downloads")
            it[key] = checkNotNull(it[key]).replace("\"Audiobook\"", "\"Hologram\"")
        }

        assertThat(registry(dataStore).book(BookId("1"))).isEqualTo(book("1", record(FormatState.Queued)))
    }
}
