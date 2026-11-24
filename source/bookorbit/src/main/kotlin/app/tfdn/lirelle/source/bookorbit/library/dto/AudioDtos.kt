package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// Written from the shapes the server answers (its audio and read-along routes) and the recorded answers.
// Anything optional may be missing; unknown keys are ignored. A request type declares only what is sent:
// the server refuses a request body with a field it does not know.

@Serializable
internal data class AudioAssetDto(
    val assetId: String,
    val fileId: Long,
    val sequence: Int = 0,
    val format: String = "",
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val etag: String? = null,
)

@Serializable
internal data class AudioChapterDto(
    /** A string on the server (`ch_<hex>`); the anonymised fixtures hold a number, so either is read. */
    val id: JsonPrimitive,
    val title: String = "",
    val assetId: String,
    val sequence: Int = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val assetOffsetMs: Long = 0,
)

@Serializable
internal data class AudioBookDto(
    val id: Long,
    val title: String = "",
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val hasCover: Boolean = false,
)

@Serializable
internal data class AudioManifestDto(
    val revision: String,
    val book: AudioBookDto,
    val assets: List<AudioAssetDto> = emptyList(),
    val chapters: List<AudioChapterDto> = emptyList(),
    val totalDurationMs: Long? = null,
)

/** [positionMs] is inside [assetId]; [percentage] is of the whole book, 0 to 100. */
@Serializable
internal data class PlaybackStateDto(
    val assetId: String,
    val positionMs: Long = 0,
    val percentage: Double = 0.0,
    val completed: Boolean = false,
    val capturedAt: String? = null,
    val revision: Int = 0,
    val manifestRevision: String? = null,
)

/** All six fields are required by the server. */
@Serializable
internal data class PlaybackWriteDto(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: String,
    val operationId: String,
    val baseRevision: Int,
    val manifestRevision: String,
)

/** The report of a listening sitting: the 2a session body, as a listening one. */
@Serializable
internal data class ListeningSessionWriteDto(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int,
    val progressDelta: Double? = null,
    val endProgress: Double? = null,
    val sessionType: String = "listen",
    val source: String = "android",
)
