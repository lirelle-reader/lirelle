package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.feature.player.service.FakeKeptBookData
import app.tfdn.lirelle.feature.player.service.LoadRequest
import app.tfdn.lirelle.feature.player.service.NarrationStart
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val Book = BookId("42")
private val ReadAloud = FileId("1")
private const val Marker = "epubcfi(/6/4!/4/2[s2]/1:0)"

/** Where a book stands, as the test says. */
class FakeCarPlaces : CarPlaces {
    var listened = AudiobookStanding(finished = false)
    var narrated = NarrationStanding(heardAt = null, marker = null)

    override suspend fun audiobook(bookId: BookId) = listened

    override suspend fun narration(fileId: FileId) = narrated
}

/** Which medium a book tapped in the car plays, and where it starts. */
class CarStartTest {

    private val library = FakeLibraryRepository()
    private val kept = FakeKeptBookData()
    private val places = FakeCarPlaces()

    /** The page of the tests has a read-aloud file (1) and an audio file (2). */
    private val both = sampleBookDetail(id = "42")
    private val audioOnly = both.copy(files = both.files.filter { it.kind == BookFileKind.Audio })
    private val readAloudOnly = both.copy(files = both.files.filter { it.kind == BookFileKind.ReadAlongEpub })

    private fun at(time: String) = Instant.parse("2026-11-25T$time:00Z")

    private suspend fun request(
        page: app.tfdn.lirelle.core.library.model.BookDetail? = both,
        capabilities: SourceCapabilities = SourceCapabilities.Everything,
    ): LoadRequest? {
        page?.let { library.bookResult = ApiResult.Success(it) }
        return CarStart(library, capabilities, kept, places).requestFor(Book)
    }

    @Test
    fun `a book with an audiobook only plays it where it was left`() = runTest {
        assertThat(request(audioOnly)).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
    }

    @Test
    fun `a finished audiobook starts over, as on the phone`() = runTest {
        places.listened = AudiobookStanding(finished = true)

        assertThat(request(audioOnly)).isEqualTo(LoadRequest.Audiobook(Book, fromStart = true))
    }

    @Test
    fun `a book with a narration only plays it, from its beginning when it was never opened`() = runTest {
        assertThat(request(readAloudOnly)).isEqualTo(LoadRequest.Narration(Book, ReadAloud, NarrationStart.Resume))
    }

    @Test
    fun `with both and neither ever listened to, the audiobook plays`() = runTest {
        assertThat(request()).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
    }

    @Test
    fun `with both, the audiobook plays even when the narration was heard`() = runTest {
        places.narrated = NarrationStanding(heardAt = at("11:00"), marker = null)

        assertThat(request()).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
    }

    @Test
    fun `with both, the audiobook plays even when the text was read`() = runTest {
        places.narrated = NarrationStanding(heardAt = null, marker = PageMarker(Marker, at("11:00")))
        assertThat(request()).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))

        // Read further since the narration was heard: the marker starts no narration either.
        places.narrated = NarrationStanding(heardAt = at("10:00"), marker = PageMarker(Marker, at("11:00")))
        assertThat(request()).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
    }

    @Test
    fun `with both, a finished audiobook starts over, whatever the narration`() = runTest {
        places.listened = AudiobookStanding(finished = true)
        places.narrated = NarrationStanding(heardAt = at("11:00"), marker = PageMarker(Marker, at("12:00")))

        assertThat(request()).isEqualTo(LoadRequest.Audiobook(Book, fromStart = true))
    }

    @Test
    fun `a narration starts at the page marker when the book was read further since it was heard`() = runTest {
        places.narrated = NarrationStanding(heardAt = at("10:00"), marker = PageMarker(Marker, at("11:00")))
        assertThat(request(readAloudOnly))
            .isEqualTo(LoadRequest.Narration(Book, ReadAloud, NarrationStart.AtMarker(Marker)))

        // Heard since: where the narration was left.
        places.narrated = NarrationStanding(heardAt = at("12:00"), marker = PageMarker(Marker, at("11:00")))
        assertThat(request(readAloudOnly)).isEqualTo(LoadRequest.Narration(Book, ReadAloud, NarrationStart.Resume))
    }

    @Test
    fun `a marker that is only a percentage names no sentence - the narration is taken up where it was`() = runTest {
        places.narrated = NarrationStanding(heardAt = at("10:00"), marker = PageMarker(null, at("11:00")))

        assertThat(request(readAloudOnly)).isEqualTo(LoadRequest.Narration(Book, ReadAloud, NarrationStart.Resume))
    }

    @Test
    fun `a source without a kind of listening does not offer it`() = runTest {
        val audiobooksOnly = SourceCapabilities.Everything.copy(readAlong = false)
        val readAloudsOnly = SourceCapabilities.Everything.copy(audiobooks = false)
        places.narrated = NarrationStanding(heardAt = at("11:00"), marker = null)

        assertThat(request(capabilities = audiobooksOnly)).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
        assertThat(request(capabilities = readAloudsOnly))
            .isEqualTo(LoadRequest.Narration(Book, ReadAloud, NarrationStart.Resume))
        assertThat(request(capabilities = KomgaLikeCapabilities)).isNull()
    }

    @Test
    fun `a book with nothing to listen to, or whose page cannot be had, starts nothing`() = runTest {
        assertThat(request(both.copy(files = emptyList()))).isNull()

        library.bookResult = ApiResult.Failure(ApiError.Unexpected)
        assertThat(request(page = null)).isNull()
    }

    @Test
    fun `without the server, a downloaded book starts from the page kept with it`() = runTest {
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(request(page = null)).isNull()

        kept.details[Book] = audioOnly
        assertThat(request(page = null)).isEqualTo(LoadRequest.Audiobook(Book, fromStart = false))
    }
}
