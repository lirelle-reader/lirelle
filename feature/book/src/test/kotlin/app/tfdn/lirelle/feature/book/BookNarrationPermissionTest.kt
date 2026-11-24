package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.offline.FileKeptBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.download.BookDownloadUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The narration of a read-along book, for an account that may not download: the reader gives it the book's plain
 * EPUB, which has no narration, so the page offers none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookNarrationPermissionTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val read = mutableListOf<BookId>()
    private val listened = mutableListOf<BookId>()
    private val narrated = mutableListOf<BookId>()

    private val plain = BookFile(FileId("3"), BookFileKind.Text, "EPUB", 900_000, false, null, null)

    /** A read-along EPUB and an audiobook. */
    private val withAudiobook = sampleBookDetail()

    /** A read-along EPUB alone: its narration is the only audio of the book. */
    private val narrationOnly = withAudiobook.copy(files = withAudiobook.files.filter { it.kind != BookFileKind.Audio })

    private val barred = BookDownloadUiState(allowed = false)

    /** The plain EPUB beside, which the source gives an account that may not download in the read-along's place. */
    private fun BookDetail.withPlainEpub() = copy(files = files + plain, readerFileWithoutDownload = plain)

    private fun show(book: BookDetail, download: DownloadPart) {
        val starts = BookStarts(
            onRead = { id, _ -> read += id },
            onListen = { id, _ -> listened += id },
            onNarrate = { narrated += it },
        )
        val reading = BookReading(42, lastReadAt = null, totalSeconds = 0, sessionCount = 0)
        compose.setContent {
            LirelleTheme { ActionsBlock(book, reading, Loadable.Ready(null), starts, download) }
        }
    }

    /**
     * Whether the page says the reader can narrate the book: the icon of Read has its sound waves, and Read says
     * so aloud. It is what "Read and listen" being on the page used to say.
     */
    private fun assertReaderNarrates(narrated: Boolean) {
        compose.assertReadIconNarrated(narrated)
        compose.onNodeWithTag(MainActionTag).assertContentDescriptionEquals(
            context.spokenAction(R.string.action_read, R.string.status_resume_percent_spoken, 42, narrated = narrated),
        )
    }

    @Test
    fun `without the permission, a book with a plain EPUB and no audio file is only read`() {
        show(narrationOnly.withPlainEpub(), DownloadPart(barred))

        compose.onNodeWithTag(ListenActionTag).assertDoesNotExist()
        assertReaderNarrates(narrated = false)
        compose.onNodeWithTag(MainActionTag).assertIsEnabled().performClick()
        assertThat(read).containsExactly(BookId("12"))
    }

    @Test
    fun `without the permission, a book with a plain EPUB keeps the listening of its audio files`() {
        show(withAudiobook.withPlainEpub(), DownloadPart(barred))

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled().performClick()
        assertReaderNarrates(narrated = false)
        assertThat(listened).containsExactly(BookId("12"))
        assertThat(narrated).isEmpty()
    }

    @Test
    fun `without the permission, a book without a plain EPUB keeps its narration, which the reader refuses`() {
        show(narrationOnly, DownloadPart(barred))

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled().performClick()
        assertReaderNarrates(narrated = true)
        assertThat(narrated).containsExactly(BookId("12"))
    }

    @Test
    fun `with the permission, a plain EPUB beside the read-along one changes nothing`() {
        show(narrationOnly.withPlainEpub(), DownloadPart(BookDownloadUiState(allowed = true)))

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled().performClick()
        assertReaderNarrates(narrated = true)
        assertThat(narrated).containsExactly(BookId("12"))
    }

    @Test
    fun `without the permission, a text the device keeps is the read-along one, and is narrated`() {
        show(narrationOnly.withPlainEpub(), DownloadPart(barred.copy(keepsText = true)))

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled().performClick()
        assertReaderNarrates(narrated = true)
        assertThat(narrated).containsExactly(BookId("12"))
    }

    /** The page as the device keeps it with a download, and reads it back without the server. */
    private fun BookDetail.keptAndReadBack(): BookDetail = runBlocking {
        val noMaps = object : ReadAlongMapStore {
            override suspend fun map(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? = null
        }
        val files = DownloadFiles(folder.newFolder("downloads"))
        FileKeptBookData(files, OkHttpClient(), noMaps, NothingRecorded).keep(this@keptAndReadBack)
        checkNotNull(FileKeptBookData(files, OkHttpClient(), noMaps, NothingRecorded).detail(id))
    }

    /** No download recorded: the page is kept as it is given. */
    private object NothingRecorded : DownloadRegistry {
        override val books: Flow<List<DownloadedBook>> = flowOf(emptyList())

        override suspend fun book(bookId: BookId): DownloadedBook? = null

        override suspend fun update(bookId: BookId, change: (DownloadedBook?) -> DownloadedBook?) = Unit

        override suspend fun clear() = Unit
    }

    @Test
    fun `without the permission nor the server, the page of a kept book narrates what the device has`() {
        val kept = setOf(DownloadFormat.Text, DownloadFormat.Narration)
        val page = narrationOnly.withPlainEpub().keptAndReadBack()
        assertThat(page.readerFileWithoutDownload).isEqualTo(plain)
        show(page, DownloadPart(barred, kept = kept))

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled()
        assertReaderNarrates(narrated = true)
    }
}
