package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.LibraryDto
import app.tfdn.lirelle.source.kavita.library.utcInstant
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.RecordedOn
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** What Kavita says of reading is read with defaults, on both versions, and its routes send what they should. */
class KavitaReadingDtosTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): KavitaReadingApi =
        KavitaApis(OkHttpClient(), KavitaJson, KavitaReadingApi::class.java).forServer(server.serverUrl)

    private inline fun <reified T> read(name: String, version: RecordedOn = RecordedOn.Latest): T =
        KavitaJson.decodeFromString<T>(fixtureBody(name, version))

    @Test
    fun `a progress reads its place and its date`() {
        val progress = read<ProgressDto>("progress-pdf")

        assertThat(progress.pageNum).isEqualTo(90)
        assertThat(progress.chapterId).isEqualTo(1355)
        assertThat(progress.bookScrollId).isNull()
        assertThat(utcInstant(progress.lastModifiedUtc)).isEqualTo(Instant.parse("2026-11-26T08:27:17.927461Z"))
    }

    @Test
    fun `a chapter never opened has no date`() {
        for (version in RecordedOn.entries) {
            val progress = read<ProgressDto>("progress-never", version)

            assertThat(progress.pageNum).isEqualTo(0)
            assertThat(utcInstant(progress.lastModifiedUtc)).isNull()
        }
    }

    @Test
    fun `a PDF chapter has its PDF file`() {
        val file = read<ChapterDto>("chapter-pdf").files.single()

        assertThat(file.format).isEqualTo(4)
        assertThat(file.extension).isEqualTo(".pdf")
    }

    @Test
    fun `the history is read on the latest version`() {
        val items = read<List<HistoryItemDto>>("history")

        assertThat(items).hasSize(5)
        assertThat(items.any { it.isGeneratedSession }).isTrue()
        val chapter = items.first().chapters.first()
        assertThat(chapter.chapterId).isEqualTo(535)
        assertThat(chapter.durationSeconds).isGreaterThan(0)
        assertThat(items.first().localDate).isEqualTo("2026-11-26T00:00:00")
    }

    @Test
    fun `the history of the oldest version has no generated session`() {
        val items = read<List<HistoryItemDto>>("history", RecordedOn.Minimum)

        assertThat(items).isNotEmpty()
        assertThat(items.none { it.isGeneratedSession }).isTrue()
        assertThat(items.first().chapters.first().chapterId).isGreaterThan(0)
    }

    @Test
    fun `the history of a series has one generated item`() {
        val item = read<List<HistoryItemDto>>("history-series-asterix").single()

        assertThat(item.isGeneratedSession).isTrue()
        assertThat(item.chapters.single().completed).isTrue()
    }

    @Test
    fun `the default reading profile is the default kind, left to right`() {
        for (version in RecordedOn.entries) {
            val profile = read<ReadingProfileDto>("reading-profile-manga", version)

            assertThat(profile.kind).isEqualTo(0)
            assertThat(profile.readingDirection).isEqualTo(0)
        }
    }

    @Test
    fun `a library tells its type`() {
        val libraries = KavitaJson.decodeFromString(
            ListSerializer(LibraryDto.serializer()),
            fixtureBody("libraries"),
        )

        assertThat(libraries.associate { it.name to it.type }).containsExactly("BD", 1, "Livres", 2, "Mangas", 0)
    }

    @Test
    fun `a progress saved is accepted with a true body, and with none`() = runBlocking {
        server.enqueueJson("true")
        server.enqueueDone()

        assertThat(api().saveProgress(ProgressDto(chapterId = 44, pageNum = 3)).isSuccessful).isTrue()
        assertThat(api().saveProgress(ProgressDto(chapterId = 44, pageNum = 4)).isSuccessful).isTrue()
        assertThat(server.takeRequest().body?.utf8()).contains("\"pageNum\":3")
    }

    @Test
    fun `the libraries of a history are one repeated parameter`() = runBlocking {
        server.enqueueJson("[]")

        api().history(libraries = listOf(1, 2, 3), zone = "Europe/Paris", page = 1, size = 1000)

        val url = server.takeRequest().url
        assertThat(url.queryParameterValues("Libraries")).containsExactly("1", "2", "3").inOrder()
        assertThat(url.queryParameter("TimeZoneId")).isEqualTo("Europe/Paris")
        assertThat(url.queryParameter("PageSize")).isEqualTo("1000")
    }
}
