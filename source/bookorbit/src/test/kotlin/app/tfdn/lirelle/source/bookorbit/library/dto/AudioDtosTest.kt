package app.tfdn.lirelle.source.bookorbit.library.dto

import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AudioDtosTest {

    private inline fun <reified T> recorded(name: String): T = ApiJson.decodeFromString(Fixtures.recorded(name))

    @Test
    fun `a single-file manifest has one asset and its chapters on the book timeline`() {
        val manifest = recorded<AudioManifestDto>("audio-manifest-m4b")

        assertThat(manifest.book.id).isEqualTo(1)
        assertThat(manifest.revision).hasLength(64)
        assertThat(manifest.assets.single().assetId).isEqualTo("aud_2")
        assertThat(manifest.assets.single().durationMs).isEqualTo(38_190_000)
        assertThat(manifest.chapters.first().endMs).isEqualTo(797_650)
        assertThat(manifest.chapters[1].assetOffsetMs).isEqualTo(797_650)
        assertThat(manifest.chapters.all { it.assetId == "aud_2" }).isTrue()
    }

    @Test
    fun `an mp3 manifest has one asset per file and total duration`() {
        val manifest = recorded<AudioManifestDto>("audio-manifest-mp3")

        assertThat(manifest.assets.map { it.sequence }).isInOrder()
        assertThat(manifest.assets.size).isGreaterThan(1)
        assertThat(manifest.assets.first().format).isEqualTo("mp3")
        assertThat(manifest.totalDurationMs).isEqualTo(51_750_000)
    }

    @Test
    fun `a playback state decodes whatever keys are missing`() {
        val state = ApiJson.decodeFromString<PlaybackStateDto>("""{"assetId":"aud_2","extra":1}""")

        assertThat(state).isEqualTo(PlaybackStateDto(assetId = "aud_2"))
    }

    @Test
    fun `a recorded playback state keeps its revision and manifest revision`() {
        val state = recorded<PlaybackStateDto>("audio-playback")

        assertThat(state.revision).isEqualTo(2)
        assertThat(state.positionMs).isEqualTo(900_000)
        assertThat(state.capturedAt).isEqualTo("2026-11-15T09:00:00.000Z")
        assertThat(state.manifestRevision).hasLength(64)
    }

    @Test
    fun `a write sends its six fields and nothing else`() {
        val text = ApiJson.encodeToString(PlaybackWriteDto("aud_2", 1, "2026-11-15T09:00:00Z", "op", 0, "rev"))

        assertThat(Json.parseToJsonElement(text).jsonObject.keys).containsExactly(
            "assetId", "positionMs", "capturedAt", "operationId", "baseRevision", "manifestRevision",
        )
    }

    @Test
    fun `a listening session is a 2a session typed listen`() {
        val body = Json.parseToJsonElement(
            ApiJson.encodeToString(ListeningSessionWriteDto("s", "a", "b", 5, progressDelta = 1.0, endProgress = 2.0)),
        ).jsonObject

        assertThat(body.keys).containsExactly(
            "sessionId", "startedAt", "endedAt", "durationSeconds", "progressDelta", "endProgress",
            "sessionType", "source",
        )
        assertThat(body.getValue("sessionType").jsonPrimitive.content).isEqualTo("listen")
        assertThat(body.getValue("source").jsonPrimitive.content).isEqualTo("android")
    }
}
