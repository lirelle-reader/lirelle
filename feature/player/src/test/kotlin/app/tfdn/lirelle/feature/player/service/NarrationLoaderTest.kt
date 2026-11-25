package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Gives [map] for whatever is asked, and keeps what was asked. */
class FakeMapStore(var map: ReadAlongMap? = ReadAlongMaps.inventedMap()) : ReadAlongMapStore {
    val asked = mutableListOf<Triple<BookId, FileId, String?>>()

    /** Thrown instead of answering, when set. */
    var failure: Exception? = null

    override suspend fun map(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? {
        asked += Triple(bookId, fileId, contentVersion)
        failure?.let { throw it }
        return map
    }
}

@RunWith(AndroidJUnit4::class)
class NarrationLoaderTest {

    private val maps = FakeMapStore()
    private val library = FakeLibraryRepository().apply {
        val page = sampleBookDetail(id = "42")
        bookResult = ApiResult.Success(
            page.copy(
                title = "The Long Road",
                coverUrl = "https://books.example/c/42",
                audioCover = MediumCover("https://books.example/audio/42", "https://books.example/audio/42/s"),
                files = page.files.map { if (it.id == FileId("1")) it.copy(contentVersion = "v7") else it },
            ),
        )
    }
    private val reader = FakeReaderRepository()
    private val narrations = InMemoryNarrationStore()
    private val settings = InMemoryPlayerSettingsStore()
    private val readAlong = FakeReadAlongRepository()
    private val loader = NarrationLoader(maps, library, reader, narrations, settings, readAlong, nothingKept())

    private fun deviceAt(sectionIndex: Int, fragment: String, at: String, seconds: Double = 0.0) {
        narrations.narrations[FileId("1")] = LocalNarration(
            fileId = FileId("1"),
            place = NarrationPlace(seconds, fragment, sectionIndex),
            percent = 10.0,
            at = Instant.parse(at),
        )
    }

    private fun serverAt(sectionIndex: Int, fragment: String, at: String?, seconds: Double = 0.0) {
        reader.positionResult = ApiResult.Success(
            ReadingPosition(
                fileId = FileId("1"),
                cfi = null,
                percent = 0.0,
                updatedAt = null,
                narration = NarrationPlace(seconds, fragment, sectionIndex),
                narrationUpdatedAt = at?.let(Instant::parse),
            ),
        )
    }

    @Test
    fun `a narration plays the read-along file's segments with the book's page and its own cover`() = runTest {
        val map = ReadAlongMaps.map("mp3", contentVersion = "v7")
        maps.map = map

        val book = loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!

        assertThat(maps.asked).containsExactly(Triple(BookId("42"), FileId("1"), "v7"))
        assertThat(book.bookId).isEqualTo(BookId("42"))
        assertThat(book.source).isEqualTo(BookSource.Narration(FileId("1"), map))
        assertThat(book.seriesId).isNull()
        val playlist = book.playlist
        assertThat(playlist.manifest.title).isEqualTo("The Long Road")
        assertThat(playlist.manifest.authors).containsExactly("Ursula Writer")
        assertThat(playlist.manifest.narrators).containsExactly("A Narrator")
        // The book's own cover, not the audiobook's.
        assertThat(playlist.coverUrl).isEqualTo("https://books.example/c/42")
        assertThat(playlist.artworkUrl).isEqualTo("https://books.example/c/42")
        assertThat(playlist.items).hasSize(12)
        assertThat(playlist.items.first().localConfiguration!!.uri.toString()).isEqualTo(
            "https://books.example/narration/42/1/OEBPS/Audio/track009.mp3",
        )
        assertThat(playlist.items.last().clippingConfiguration.endPositionMs).isEqualTo(76_340)
    }

    @Test
    fun `without a read-along file, or without its map, there is nothing to play`() = runTest {
        assertThat(loader.load(BookId("42"), FileId("2"), NarrationStart.Resume)).isNull()

        maps.map = null
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)).isNull()

        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)).isNull()
    }

    @Test
    fun `a narration starts at the sentence asked, or at its section's first sentence`() = runTest {
        val book = BookId("42")
        assertThat(loader.load(book, FileId("1"), NarrationStart.AtSentence(1, "s2"))!!.startMs).isEqualTo(4_000)
        assertThat(loader.load(book, FileId("1"), NarrationStart.AtSentence(3, "text/three.xhtml#s5"))!!.startMs)
            .isEqualTo(19_000)
        assertThat(loader.load(book, FileId("1"), NarrationStart.AtSentence(3, "unknown"))!!.startMs).isEqualTo(15_000)
        // A section without narration: the next narrated one.
        assertThat(loader.load(book, FileId("1"), NarrationStart.AtSentence(2, "nothing"))!!.startMs).isEqualTo(15_000)
        assertThat(loader.load(book, FileId("1"), NarrationStart.AtSentence(9, "nothing"))!!.startMs).isEqualTo(0)
    }

    @Test
    fun `a narration resumes at the device's place when it is the newer one`() = runTest {
        deviceAt(3, "text/three.xhtml#s5", at = "2026-11-15T10:00:00Z")
        serverAt(1, "text/one.xhtml#s2", at = "2026-11-15T09:00:00Z")

        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(19_000)
    }

    @Test
    fun `a narration resumes at the server's place when it is the newer one`() = runTest {
        deviceAt(3, "text/three.xhtml#s5", at = "2026-11-15T10:00:00Z")
        serverAt(1, "text/one.xhtml#s2", at = "2026-11-15T11:00:00Z")

        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(4_000)
    }

    @Test
    fun `a place whose sentence is not known resumes on the narration clock`() = runTest {
        serverAt(1, "text/one.xhtml#gone", at = "2026-11-15T11:00:00Z", seconds = 10.5)

        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(10_500)
    }

    @Test
    fun `with no place anywhere a narration starts at its beginning`() = runTest {
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(0)

        // The server answers without a narration place.
        reader.positionResult = ApiResult.Success(ReadingPosition(FileId("1"), null, 0.0, null))
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(0)
    }

    @Test
    fun `the device's place stands when the server's has no date`() = runTest {
        deviceAt(3, "text/three.xhtml#s4", at = "2026-11-15T10:00:00Z")
        serverAt(1, "text/one.xhtml#s2", at = null)

        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.startMs).isEqualTo(15_000)
    }

    @Test
    fun `a narration plays at its own speed, else the book's, else normal speed`() = runTest {
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.speed).isEqualTo(1f)

        settings.speeds[BookId("42")] = 1.5f
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.speed).isEqualTo(1.5f)

        settings.narrationSpeeds[BookId("42")] = 1.25f
        assertThat(loader.load(BookId("42"), FileId("1"), NarrationStart.Resume)!!.speed).isEqualTo(1.25f)
    }

    @Test
    fun `a narration asked at the book's page marker starts at the sentence the marker is in`() = runTest {
        maps.map = ReadAlongMaps.inventedMap()
        deviceAt(1, "text/one.xhtml#s1", "2026-11-10T10:00:00Z")

        // In the third sentence of section 1, which starts 9 s into the narration.
        val inside = loader.load(BookId("42"), FileId("1"), NarrationStart.AtMarker("epubcfi(/6/4!/4/2[s3]/1:7)"))
        // In a section without narration: the first sentence of the next narrated one, at 15 s.
        val skipped = loader.load(BookId("42"), FileId("1"), NarrationStart.AtMarker("epubcfi(/6/6!/4/2/1:0)"))

        assertThat(inside?.startMs).isEqualTo(9_000)
        assertThat(skipped?.startMs).isEqualTo(15_000)
    }

    @Test
    fun `a marker that names no sentence leaves the narration where it was, never at its section's start`() = runTest {
        maps.map = ReadAlongMaps.inventedMap()
        deviceAt(1, "text/one.xhtml#s2", "2026-11-10T10:00:00Z")

        // Between the sentences of section 1: the place is in the page, which nothing here can read.
        val between = loader.load(BookId("42"), FileId("1"), NarrationStart.AtMarker("epubcfi(/6/4!/4/2[p1]/3:1)"))

        assertThat(between?.startMs).isEqualTo(4_000)
    }
}
