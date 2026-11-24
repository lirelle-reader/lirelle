package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.library.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

// The answers are hand-written from the shape of the recorded positions, until a comic's are recorded.
class DefaultReaderRepositoryComicsTest {

    private val server = MockWebServer()
    private lateinit var repository: DefaultReaderRepository

    @Before
    fun setUp() {
        server.start()
        repository = DefaultReaderRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, ReaderApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { server.serverUrl },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun enqueueHandmade(name: String) = server.enqueueJson(Fixtures.read("handmade/$name.json"))

    @Test
    fun `the position of a comic has a page and no place`() = runBlocking {
        enqueueHandmade("comic-progress")

        assertThat(repository.position(FileId("31"))).isEqualTo(
            ApiResult.Success(
                ReadingPosition(
                    FileId("31"),
                    cfi = null,
                    percent = 25.0,
                    Instant.parse("2026-11-17T09:12:44.501Z"),
                    page = 12,
                ),
            ),
        )
    }

    @Test
    fun `a position with a page is sent with the page and no cfi`() = runBlocking {
        enqueueHandmade("comic-progress-saved")

        val result = repository.savePosition(FileId("31"), cfi = null, percent = 25.0, page = 12)

        assertThat(result)
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-17T09:12:44.501Z"))))
        val body = server.takeRequest().bodyText()
        assertThat(body).doesNotContain("cfi")
        assertThat(json(body))
            .isEqualTo(json("""{"writes":[{"fileId":31,"kind":"text","percentage":25.0,"text":{"page":12}}]}"""))
    }

    @Test
    fun `a position with a place is sent without a page`() = runBlocking {
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")

        repository.savePosition(FileId("12"), "epubcfi(/6/8!/4/2/1:0)", 42.5)

        val body = server.takeRequest().bodyText()
        assertThat(body).doesNotContain("page")
        assertThat(json(body)).isEqualTo(
            json(
                """{"writes":[
                    {"fileId":12,"kind":"text","percentage":42.5,"text":{"cfi":"epubcfi(/6/8!/4/2/1:0)"}}]}""",
            ),
        )
    }

    @Test
    fun `the next album is asked among the comics`() = runBlocking {
        enqueueHandmade("comic-next-volume")

        assertThat(repository.nextVolume(SeriesId("3"), BookId("5"), VolumeGroup.Comics))
            .isEqualTo(ApiResult.Success(NextVolume(BookId("9"), "Album 2", "2")))
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series/3/books/5/next?formatGroup=cbx")
    }

    @Test
    fun `the next volume of a book is asked among the EPUBs, unless told`() = runBlocking {
        server.enqueueJson("""{"next":null}""")
        server.enqueueJson("""{"next":null}""")

        repository.nextVolume(SeriesId("3"), BookId("5"))
        repository.nextVolume(SeriesId("3"), BookId("5"), VolumeGroup.Epub)

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series/3/books/5/next?formatGroup=epub")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series/3/books/5/next?formatGroup=epub")
    }
}
