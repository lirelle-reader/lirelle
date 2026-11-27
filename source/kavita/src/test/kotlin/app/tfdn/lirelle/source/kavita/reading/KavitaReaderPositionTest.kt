package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.jsonBody
import app.tfdn.lirelle.source.kavita.testing.route
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A chapter of the test server: library 1, series 7, volume 7, chapter 44, file 44 (a CBZ of 53 pages). */
private val Album = FileId("1-7-7-44-44")
private val Now = Instant.parse("2026-11-27T10:00:00Z")

/** When the outbox's late places were made: before Kavita's place of [KavitaDate]. */
private val Edited = Instant.parse("2026-11-26T08:00:00Z")
private const val KavitaDate = "2026-11-26T08:27:17.927461"
private val KavitaInstant = Instant.parse("2026-11-26T08:27:17.927461Z")

private const val Progress = "GET /api/Reader/get-progress"
private const val Chapter = "GET /api/Chapter"
private const val Save = "POST /api/Reader/progress"

/** The place of a book kept on Kavita: read, written at once, or written late only over an older place. */
class KavitaReaderPositionTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var reader: KavitaReader

    @Before
    fun setUp() {
        server.start()
        routes = KavitaRoutes(server)
        reader = routes.reader(now = { Now })
        routes.answer(Save) { MockResponse(code = 200, body = "true") }
    }

    @After
    fun tearDown() = server.close()

    private fun comic() = routes.answer(Chapter, fixtureBody("chapter-asterix"))

    /** The comic's chapter with its file an EPUB: 53 spine items. */
    private fun epub() {
        val chapter = Json.parseToJsonElement(fixtureBody("chapter-asterix")).jsonObject
        val file = chapter.getValue("files").jsonArray.first().jsonObject
        val epubFile = JsonObject(file + mapOf("format" to JsonPrimitive(3), "extension" to JsonPrimitive(".epub")))
        routes.answer(Chapter, JsonObject(chapter + ("files" to JsonArray(listOf(epubFile)))).toString())
    }

    private fun held(pageNum: Int, at: String = KavitaDate, scroll: String? = null) {
        val place = ProgressDto(
            volumeId = 7,
            chapterId = 44,
            pageNum = pageNum,
            seriesId = 7,
            libraryId = 1,
            bookScrollId = scroll,
            lastModifiedUtc = at,
        )
        routes.answer(Progress, Json.encodeToString(ProgressDto.serializer(), place))
    }

    private fun save(cfi: String? = null, percent: Double = 9.4, editedAt: Instant? = null, page: Int? = 5) =
        runBlocking { reader.savePosition(Album, cfi, percent, editedAt, page) }

    private fun routesAsked(): List<String> = routes.requests().map { it.route() }

    /** The place posted, every field as sent. */
    private fun posted(): Map<String, JsonElement> =
        routes.requests().single { it.route() == Save }.jsonBody().jsonObject

    private fun placeOf(json: String): Map<String, JsonElement> = Json.parseToJsonElement(json).jsonObject.toMap()

    @Test
    fun `a comic's place is its page from 1, read with the chapter`() {
        comic()
        held(pageNum = 4)

        val position = runBlocking { reader.position(Album) }

        assertThat(position).isEqualTo(
            ApiResult.Success(
                ReadingPosition(Album, cfi = null, percent = 5 * 100.0 / 53, updatedAt = KavitaInstant, page = 5),
            ),
        )
        val requests = routes.requests()
        assertThat(requests.map { it.route() }).containsExactly(Progress, Chapter)
        assertThat(requests.map { it.url.queryParameter("chapterId") }).containsExactly("44", "44")
    }

    @Test
    fun `an EPUB's place is the CFI the app wrote`() {
        epub()
        held(pageNum = 6, scroll = "epubcfi(/6/14!/4/2/1:0)")

        val position = (runBlocking { reader.position(Album) } as ApiResult.Success).value

        assertThat(position.cfi).isEqualTo("epubcfi(/6/14!/4/2/1:0)")
        assertThat(position.page).isNull()
    }

    @Test
    fun `the place the user is at is posted at once, from 0`() {
        comic()

        assertThat(save()).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Now)))

        assertThat(routesAsked()).containsExactly(Chapter, Save).inOrder()
    }

    @Test
    fun `the place posted carries the file's ids`() {
        comic()

        save()

        assertThat(posted()).isEqualTo(
            placeOf("""{"libraryId":1,"seriesId":7,"volumeId":7,"chapterId":44,"pageNum":4,"bookScrollId":null}"""),
        )
    }

    @Test
    fun `the first page is posted as page 0, with every id`() {
        comic()

        save(percent = 1.9, page = 1)

        assertThat(posted()).isEqualTo(
            placeOf("""{"libraryId":1,"seriesId":7,"volumeId":7,"chapterId":44,"pageNum":0,"bookScrollId":null}"""),
        )
    }

    @Test
    fun `an EPUB place without a CFI is posted as spine item 0`() {
        epub()

        save(cfi = null, percent = 0.0, page = null)

        val place = posted()
        assertThat(place["pageNum"]).isEqualTo(JsonPrimitive(0))
        assertThat(place["bookScrollId"]).isEqualTo(JsonNull)
    }

    @Test
    fun `the chapter is asked once for the places written while it is read`() {
        comic()

        save(page = 5)
        save(page = 6)

        assertThat(routesAsked()).containsExactly(Chapter, Save, Save).inOrder()
    }

    @Test
    fun `the chapter is asked again at the opening, and kept for the places written next`() {
        comic()
        held(pageNum = 4)

        runBlocking { reader.position(Album) }
        runBlocking { reader.position(Album) }
        save(page = 6)

        assertThat(routesAsked().filter { it == Chapter }).hasSize(2)
    }

    @Test
    fun `the end is posted as the page count, over a place before it`() {
        comic()
        held(pageNum = 52)

        assertThat(save(percent = 100.0, page = 53)).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Now)))

        assertThat(posted()["pageNum"]).isEqualTo(JsonPrimitive(53))
    }

    @Test
    fun `the end Kavita already holds is saved at Kavita's date, not posted again`() {
        comic()
        held(pageNum = 53)

        assertThat(save(percent = 100.0, page = 53))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, KavitaInstant)))

        assertThat(routesAsked()).doesNotContain(Save)
    }

    @Test
    fun `the end of a chapter never opened is posted`() {
        comic()
        routes.answer(Progress, fixtureBody("progress-never"))

        save(percent = 100.0, page = 53)

        assertThat(posted()["pageNum"]).isEqualTo(JsonPrimitive(53))
    }

    @Test
    fun `an EPUB is posted as its spine item and its CFI`() {
        epub()

        save(cfi = "epubcfi(/6/14!/4/2/1:0)", percent = 40.0, page = null)

        val place = posted()
        assertThat(place["pageNum"]).isEqualTo(JsonPrimitive(6))
        assertThat(place["bookScrollId"]).isEqualTo(JsonPrimitive("epubcfi(/6/14!/4/2/1:0)"))
    }

    @Test
    fun `a late place is set aside when Kavita holds a newer one`() {
        comic()
        held(pageNum = 9)

        assertThat(save(editedAt = Edited))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded, KavitaInstant)))

        assertThat(routesAsked()).doesNotContain(Save)
    }

    @Test
    fun `a late place Kavita already holds is saved, at Kavita's date`() {
        comic()
        held(pageNum = 4)

        assertThat(save(editedAt = Edited))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, KavitaInstant)))

        assertThat(routesAsked()).doesNotContain(Save)
    }

    @Test
    fun `a late place Kavita already holds from before is saved, at Kavita's date`() {
        comic()
        held(pageNum = 4, at = "2026-11-26T07:00:00")

        assertThat(save(editedAt = Edited))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-26T07:00:00Z"))))

        assertThat(routesAsked()).doesNotContain(Save)
    }

    @Test
    fun `a late place is posted over an older one, saved at when Kavita took it`() {
        comic()
        held(pageNum = 2, at = "2026-11-26T07:00:00")

        assertThat(save(editedAt = Edited)).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Now)))

        assertThat(posted()["pageNum"]).isEqualTo(JsonPrimitive(4))
    }

    @Test
    fun `a late place of a chapter never opened is posted`() {
        comic()
        routes.answer(Progress, fixtureBody("progress-never"))

        assertThat(save(editedAt = Edited)).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Now)))

        assertThat(routesAsked()).contains(Save)
    }

    @Test
    fun `a file that is not Kavita's is not found, and nothing is asked`() {
        val other = FileId("bookorbit-12")

        assertThat(runBlocking { reader.position(other) }).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(runBlocking { reader.savePosition(other, null, 10.0, null, 2) })
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(routes.requests()).isEmpty()
    }

    @Test
    fun `a refused post is a session expired, a gone chapter not found`() {
        comic()
        routes.fail(Save, 401)

        assertThat(save()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))

        routes.fail(Save, 404)

        assertThat(save()).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `sessions are dropped, and there is no finished reading, rating nor narration`() {
        val session = ReadingSessionReport("s1", Edited, Now, activeSeconds = 60, progressDelta = 2.0, endPercent = 9.4)

        runBlocking {
            assertThat(reader.saveSession(Album, session)).isEqualTo(ApiResult.Success(Unit))
            assertThat(reader.finishedReading(BookId("1-7-7-44"))).isEqualTo(ApiResult.Success(null))
            assertThat(reader.rate(ReadId("1"), 4)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(reader.saveNarration(Album, NarrationPlace(1.0, "a.xhtml#s1", 0), 9.4))
                .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        }
        assertThat(routes.requests()).isEmpty()
    }
}
