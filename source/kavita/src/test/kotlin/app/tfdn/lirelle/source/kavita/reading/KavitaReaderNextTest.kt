package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The series of the test server: Astérix, series 7, its books `c44`, `c55`, `c56`, `c80`, `c81` and `c82`. */
private val Asterix = SeriesId("7")

/** The next book of a series, in the order of the series page. */
class KavitaReaderNextTest {

    private val server = MockWebServer()
    private lateinit var reader: KavitaReader

    @Before
    fun setUp() {
        server.start()
        val routes = KavitaRoutes(server)
        routes.answer("GET /api/Series/series-detail", fixtureBody("detail-asterix"))
        reader = routes.reader()
    }

    @After
    fun tearDown() = server.close()

    private fun next(bookId: String, series: SeriesId = Asterix, group: VolumeGroup = VolumeGroup.Epub) =
        runBlocking { reader.nextVolume(series, BookId(bookId), group) }

    @Test
    fun `the next book is the one after it in the series`() {
        assertThat(next("c44")).isEqualTo(ApiResult.Success(NextVolume(BookId("c55"), "La Serpe d'or", "2")))
    }

    @Test
    fun `the next book follows the chapters outside a volume`() {
        assertThat(next("c56"))
            .isEqualTo(ApiResult.Success(NextVolume(BookId("c80"), "Astérix et le Griffon", "39")))
    }

    @Test
    fun `the group is not read`() {
        assertThat(next("c44", group = VolumeGroup.Comics)).isEqualTo(next("c44", group = VolumeGroup.Epub))
    }

    @Test
    fun `after the last book there is none`() {
        assertThat(next("c82")).isEqualTo(ApiResult.Success(null))
    }

    @Test
    fun `a book that is not in the series has no next`() {
        assertThat(next("c999")).isEqualTo(ApiResult.Success(null))
    }

    @Test
    fun `a series that is not Kavita's is not found`() {
        assertThat(next("c44", series = SeriesId("not-a-number")))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }
}
