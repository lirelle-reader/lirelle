package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.ModelFixtures
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueRecorded
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Where `core:testing` keeps the model fixtures, from this module's folder (where a unit test runs). */
private const val ModelFixturesFolder = "../../core/testing/src/main/resources/fixtures/models"

/** The audiobooks recorded on a server whose manifests are model fixtures. */
private val Manifests = listOf("m4b", "mp3")

/** The read-along books recorded on a server whose playlists and spines are model fixtures. */
private val ReadAlongs = listOf("m4b", "mp3", "gap", "unclipped", "after")

/**
 * The model fixtures of `core:testing` ([ModelFixtures]) are exactly what this source makes of the answers recorded
 * on a server: each recorded answer goes through the real repository and its result must equal the fixture.
 *
 * To write the fixtures again (after the recorded answers or the mapping changed), run this test with the Gradle
 * property `writeModelFixtures`: `./gradlew :source:bookorbit:testDebugUnitTest -PwriteModelFixtures=true --tests
 * '*ModelFixturesTest'`. It then writes the files into `core/testing/src/main/resources/fixtures/models/` instead of
 * checking them; review and commit them.
 */
class ModelFixturesTest {

    private val server = MockWebServer()
    private val writing = System.getProperty("writeModelFixtures").toBoolean()
    private val errors = ErrorMapper(ApiJson)
    private lateinit var audio: DefaultAudioRepository
    private lateinit var readAlong: DefaultReadAlongRepository

    @Before
    fun setUp() {
        server.start()
        audio = DefaultAudioRepository(ApiProvider(OkHttpClient(), ApiJson, AudioApi::class.java), errors, {
            server.serverUrl
        })
        readAlong = DefaultReadAlongRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, ReadAlongApi::class.java),
            errors = errors,
            serverUrl = { server.serverUrl },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    /** Writes [text] as the fixture [file], or checks that [made] is what the fixture [expected] holds. */
    private fun <T> checkOrWrite(file: String, made: T, text: (T) -> String, expected: () -> T) {
        if (writing) {
            File(ModelFixturesFolder).mkdirs()
            File("$ModelFixturesFolder/$file").writeText(text(made))
        } else {
            assertWithMessage(file).that(made).isEqualTo(expected())
        }
    }

    @Test
    fun `the manifests are what the source makes of the recorded answers`() = runBlocking {
        for (name in Manifests) {
            server.enqueueRecorded("audio-manifest-$name")
            val manifest = audio.manifest(BookId("0")).value()

            checkOrWrite("manifest-$name.json", manifest, ModelFixtures::manifestText) { ModelFixtures.manifest(name) }
        }
    }

    @Test
    fun `the read-along playlists are what the source makes of the recorded answers`() = runBlocking {
        for (name in ReadAlongs) {
            server.enqueueRecorded("readalong-map-$name")
            val playlist = readAlong.playlist(BookId("0"), FileId("0")).value()

            checkOrWrite("playlist-$name.json", playlist, ModelFixtures::playlistText) { ModelFixtures.playlist(name) }
        }
    }

    @Test
    fun `the read-along spines are what the source makes of the recorded answers`() = runBlocking {
        for (name in ReadAlongs) {
            server.enqueueRecorded("readalong-info-$name")
            val spine = readAlong.spine(BookId("0"), FileId("0")).value()

            checkOrWrite("spine-$name.json", spine, ModelFixtures::spineText) { ModelFixtures.spine(name) }
        }
    }
}
