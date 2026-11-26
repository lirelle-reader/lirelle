package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.network.answer
import app.tfdn.lirelle.source.kavita.testing.bodyText
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.fixturePagination
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class KavitaLibraryApiTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): KavitaLibraryApi =
        KavitaApis(OkHttpClient(), KavitaJson, KavitaLibraryApi::class.java).forServer(server.serverUrl)

    @Test
    fun `a route of Home is asked through the one service, with its page and its total`() = runBlocking {
        server.enqueueJson(fixtureBody("ondeck20"), 200, "Pagination" to fixturePagination("ondeck20")!!)

        val response = api().onDeck(page = 1, size = 20)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.pathText()).isEqualTo("/api/Series/on-deck")
        assertThat(request.url.queryParameter("PageNumber")).isEqualTo("1")
        assertThat(request.url.queryParameter("PageSize")).isEqualTo("20")
        assertThat(response.answer()).isNotEmpty()
        assertThat(response.totalItems()).isEqualTo(9)
    }

    @Test
    fun `a series is asked by its id in the path`() = runBlocking {
        server.enqueueJson(fixtureBody("series-dune"))

        val series = api().oneSeries(261)

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/Series/261")
        assertThat(series.name).isEqualTo("Dune")
    }

    @Test
    fun `the series are asked with their filter as the body, by page`() = runBlocking {
        server.enqueueJson(fixtureBody("all-first"))
        val filter = seriesFilter(listOf(Statement(SeriesField.Libraries, Comparison.Equal, "1")))

        val series = api().series(filter, page = 1, size = 100).answer()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.pathText()).isEqualTo("/api/Series/all-v2")
        assertThat(request.url.query).isEqualTo("PageNumber=1&PageSize=100")
        assertThat(Json.parseToJsonElement(request.bodyText()).jsonObject).isEqualTo(filter)
        assertThat(series).isNotEmpty()
    }
}
