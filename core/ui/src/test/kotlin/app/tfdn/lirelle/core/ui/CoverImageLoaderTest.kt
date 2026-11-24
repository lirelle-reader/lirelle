package app.tfdn.lirelle.core.ui

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The real image loader against a server answering with the cache headers BookOrbit sends:
 * a versioned cover never changes, an unversioned one (series tiles) is good for a day, an
 * author portrait must be checked every time. And with the ones Komga sends: every image checked
 * each time with its `ETag`. In every case what is on disk is shown when the server cannot be
 * reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Images are really decoded, to tell the old one from the new one.
class CoverImageLoaderTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private var now = Instant.parse("2026-11-13T10:00:00Z")
    private lateinit var loader: ImageLoader

    @Before
    fun setUp() {
        server.start()
        // The HTTP client stamps its responses with the system clock: here, with the test's.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val at = now.toEpochMilliseconds()
            chain.proceed(chain.request()).newBuilder().sentRequestAtMillis(at).receivedResponseAtMillis(at).build()
        }.build()
        loader = newCoverImageLoader(context, client, context.cacheDir.resolve("covers"), now = { now })
    }

    @After
    fun tearDown() {
        loader.shutdown()
        server.close()
    }

    private fun enqueueImage(png: String, cacheControl: String, etag: String) {
        val headers = headersOf("Content-Type", "image/png", "Cache-Control", cacheControl, "ETag", etag)
        val image = Buffer().write(Base64.getDecoder().decode(png))
        server.enqueue(MockResponse.Builder().headers(headers).body(image).build())
    }

    private fun enqueueNotModified(cacheControl: String, etag: String) {
        server.enqueue(MockResponse(code = 304, headers = headersOf("Cache-Control", cacheControl, "ETag", etag)))
    }

    private fun later(duration: Duration) {
        now += duration
    }

    /** Loads [path] as a screen opened later would: nothing is kept in memory in between. */
    private fun load(path: String, network: CachePolicy = CachePolicy.ENABLED): ImageResult = runBlocking {
        val request = ImageRequest.Builder(context)
            .data(server.url(path).toString())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(network)
            .allowHardware(false)
            .build()
        loader.execute(request)
    }

    /** The `If-None-Match` header of each request received, in order. */
    private fun validators(): List<String?> =
        List(server.requestCount) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)).headers["If-None-Match"] }

    private fun ImageResult.colour(): Int {
        if (this is ErrorResult) throw AssertionError("The image failed to load", throwable)
        return (this as SuccessResult).image.toBitmap().getPixel(0, 0)
    }

    private fun ImageResult.source(): DataSource = (this as SuccessResult).dataSource

    // --- (1) versioned covers

    @Test
    fun `a versioned cover is asked for once and then always comes from the disk`() {
        enqueueImage(Red, Versioned, etag = "\"v1\"")

        assertThat(load(VersionedCover).colour()).isEqualTo(Color.RED)
        later(1.days)
        assertThat(load(VersionedCover).source()).isEqualTo(DataSource.DISK)
        later(ThreeYears)
        val afterYears = load(VersionedCover)

        assertThat(afterYears.source()).isEqualTo(DataSource.DISK)
        assertThat(afterYears.colour()).isEqualTo(Color.RED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // --- (2) unversioned images follow the server's cache headers

    @Test
    fun `a series cover is not asked for again within the server's max-age`() {
        enqueueImage(Red, Unversioned, etag = "\"a\"")

        load(SeriesCover)
        later(23.hours)
        val again = load(SeriesCover)

        assertThat(again.source()).isEqualTo(DataSource.DISK)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a series cover older than the max-age is checked, and kept when the server says it is the same`() {
        enqueueImage(Red, Unversioned, etag = "\"a\"")
        enqueueNotModified(Unversioned, etag = "\"a\"")

        load(SeriesCover)
        later(25.hours)
        val checked = load(SeriesCover)

        assertThat(checked.colour()).isEqualTo(Color.RED)
        assertThat(validators()).containsExactly(null, "\"a\"").inOrder()

        // Confirmed by the server, it is good for another day.
        later(23.hours)
        assertThat(load(SeriesCover).colour()).isEqualTo(Color.RED)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a series cover changed on the server shows once the max-age has passed`() {
        enqueueImage(Red, Unversioned, etag = "\"a\"")
        enqueueImage(Blue, Unversioned, etag = "\"b\"")

        assertThat(load(SeriesCover).colour()).isEqualTo(Color.RED)
        later(25.hours)

        assertThat(load(SeriesCover).colour()).isEqualTo(Color.BLUE)
        // And it is the new one that is on the disk now.
        assertThat(load(SeriesCover).colour()).isEqualTo(Color.BLUE)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an author portrait is checked on every load and shows as soon as it changes`() {
        enqueueImage(Red, Portrait, etag = "\"a\"")
        enqueueNotModified(Portrait, etag = "\"a\"")
        enqueueImage(Blue, Portrait, etag = "\"b\"")

        assertThat(load(AuthorPortrait).colour()).isEqualTo(Color.RED)
        assertThat(load(AuthorPortrait).colour()).isEqualTo(Color.RED)
        assertThat(load(AuthorPortrait).colour()).isEqualTo(Color.BLUE)

        assertThat(validators()).containsExactly(null, "\"a\"", "\"a\"").inOrder()
    }

    @Test
    fun `a Komga cover is checked with its ETag on every load, and a changed poster replaces the kept one`() {
        enqueueImage(Red, CheckEachTime, etag = "\"a\"")
        enqueueNotModified(CheckEachTime, etag = "\"a\"")
        enqueueImage(Blue, CheckEachTime, etag = "\"b\"")

        assertThat(load(KomgaSeriesCover).colour()).isEqualTo(Color.RED)
        // Its URL stays the same when its poster changes: only the check tells.
        assertThat(load(KomgaSeriesCover).colour()).isEqualTo(Color.RED)
        assertThat(load(KomgaSeriesCover).colour()).isEqualTo(Color.BLUE)
        assertThat(validators()).containsExactly(null, "\"a\"", "\"a\"").inOrder()

        // The new poster is the one kept on the disk, though its server marks it private.
        server.close()
        assertThat(load(KomgaSeriesCover).colour()).isEqualTo(Color.BLUE)
    }

    // --- (3) without the server, what is on the disk is shown

    @Test
    fun `a series cover past its max-age is still shown when the server cannot be reached`() {
        enqueueImage(Red, Unversioned, etag = "\"a\"")
        load(SeriesCover)
        later(30.days)
        server.close()

        val offline = load(SeriesCover)

        assertThat(offline).isInstanceOf(SuccessResult::class.java)
        assertThat(offline.colour()).isEqualTo(Color.RED)
    }

    @Test
    fun `an author portrait is still shown when the server cannot be reached`() {
        enqueueImage(Red, Portrait, etag = "\"a\"")
        load(AuthorPortrait)
        server.close()

        assertThat(load(AuthorPortrait).colour()).isEqualTo(Color.RED)
    }

    @Test
    fun `a Komga cover is still shown when the server cannot be reached`() {
        enqueueImage(Red, CheckEachTime, etag = "\"a\"")
        load(KomgaSeriesCover)
        later(30.days)
        server.close()

        val offline = load(KomgaSeriesCover)

        assertThat(offline).isInstanceOf(SuccessResult::class.java)
        assertThat(offline.colour()).isEqualTo(Color.RED)
    }

    @Test
    fun `a versioned cover is still shown when the server cannot be reached`() {
        enqueueImage(Red, Versioned, etag = "\"v1\"")
        load(VersionedCover)
        later(ThreeYears)
        server.close()

        assertThat(load(VersionedCover).colour()).isEqualTo(Color.RED)
    }

    @Test
    fun `an image shown while the server was failing is checked again the next time`() {
        enqueueImage(Red, Unversioned, etag = "\"a\"")
        server.enqueue(MockResponse(code = 503))
        enqueueImage(Blue, Unversioned, etag = "\"b\"")

        load(SeriesCover)
        later(25.hours)
        assertThat(load(SeriesCover).colour()).isEqualTo(Color.RED)

        assertThat(load(SeriesCover).colour()).isEqualTo(Color.BLUE)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a stale image is shown when the loader is told not to use the network`() {
        enqueueImage(Red, Portrait, etag = "\"a\"")
        load(AuthorPortrait)

        val offline = load(AuthorPortrait, network = CachePolicy.DISABLED)

        assertThat(offline.colour()).isEqualTo(Color.RED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an image never seen cannot be shown without the server`() {
        server.close()

        assertThat(load(SeriesCover)).isInstanceOf(ErrorResult::class.java)
    }

    @Test
    fun `an image the server refuses is an error even with nothing wrong with the network`() {
        server.enqueue(MockResponse(code = 404))

        assertThat(load(SeriesCover)).isInstanceOf(ErrorResult::class.java)
    }

    private companion object {
        val ThreeYears = (3 * 365).days

        // What the server answers, checked on a live BookOrbit.
        const val Versioned = "public, max-age=31536000, immutable"
        const val Unversioned = "private, max-age=86400"
        const val Portrait = "no-cache"

        const val VersionedCover = "/api/v1/books/12/thumbnail?t=ebook%3A2026-01-02"
        const val SeriesCover = "/api/v1/books/12/thumbnail"
        const val AuthorPortrait = "/api/v1/authors/9/thumbnail"

        // What Komga answers for every image of its API; its thumbnail URLs carry no version.
        const val CheckEachTime = "private, max-age=0, must-revalidate"
        const val KomgaSeriesCover = "/api/v1/series/0SR1A/thumbnail"

        // One-pixel PNG images.
        const val Red = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
        const val Blue = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYPgPAAEDAQAIicLsAAAAAElFTkSuQmCC"
    }
}
