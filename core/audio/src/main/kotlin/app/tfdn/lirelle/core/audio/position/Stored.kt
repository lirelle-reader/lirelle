package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** An instant as ISO-8601 text, so that it comes back exactly as it was written. */
internal object InstantAsText : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("app.tfdn.lirelle.core.audio.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = try {
        Instant.parse(decoder.decodeString())
    } catch (failure: DateTimeParseException) {
        throw SerializationException("Not an instant", failure)
    }
}

/**
 * A listening state's version as text. The versions before 2b kept BookOrbit's revisions as JSON numbers: a number
 * reads back as its text, so that nothing kept restarts.
 */
internal object VersionAsText : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("app.tfdn.lirelle.core.audio.Version", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = json.decodeJsonElement()
        if (element !is JsonPrimitive || element is JsonNull) throw SerializationException("Not a version")
        return element.content
    }
}

/**
 * A position as kept. The names of its fields are those of the versions before 2b, which kept BookOrbit's
 * revision as a number in `baseRevision`, 0 when none was known. Such a number reads back as its text: a stored 0
 * is the version "0", not none (BookOrbit takes it as 0 again).
 */
@Serializable
internal data class StoredPlayback(
    val bookId: BookId,
    val assetId: String,
    val positionMs: Long,
    val bookTimeMs: Long,
    @Serializable(InstantAsText::class) val at: Instant,
    val operationId: String,
    @SerialName("baseRevision") @Serializable(VersionAsText::class) val baseVersion: String? = null,
    @SerialName("manifestRevision") val contentVersion: String,
)

/**
 * The last position of each book listened to here, the version of its state the server gave each (`revisions`,
 * BookOrbit's revisions as numbers before 2b), when the last write of each the server took or may have taken was
 * captured, and the places of its last writes. A value written before the profiles also names an `owner`, which is
 * no longer read: the file is in a profile's folder, and holds nobody else's.
 */
@Serializable
internal data class StoredListening(
    val positions: List<StoredPlayback> = emptyList(),
    @SerialName("revisions") val versions: Map<BookId, @Serializable(VersionAsText::class) String> = emptyMap(),
    val written: Map<BookId, @Serializable(InstantAsText::class) Instant> = emptyMap(),
    val places: Map<BookId, List<WrittenPlace>> = emptyMap(),
)

/** What waits to be sent. A value written before the profiles also names an `owner`, which is no longer read. */
@Serializable
internal data class StoredPlaybackOutbox(val positions: List<StoredPlayback> = emptyList())

@Serializable
internal data class StoredSession(
    val fileId: FileId,
    val sessionId: String,
    @Serializable(InstantAsText::class) val startedAt: Instant,
    @Serializable(InstantAsText::class) val endedAt: Instant,
    val activeSeconds: Int,
    val progressDelta: Double? = null,
    val endPercent: Double? = null,
) {
    fun queued() = QueuedSession(
        fileId,
        ReadingSessionReport(sessionId, startedAt, endedAt, activeSeconds, progressDelta, endPercent),
    )

    companion object {
        fun of(session: QueuedSession) = with(session.report) {
            StoredSession(session.fileId, sessionId, startedAt, endedAt, activeSeconds, progressDelta, endPercent)
        }
    }
}

/**
 * The listening sessions waiting to be declared. A value written before the profiles also names an `owner`,
 * which is no longer read.
 */
@Serializable
internal data class StoredSessionQueue(val sessions: List<StoredSession> = emptyList())

internal fun LocalPlayback.stored() =
    StoredPlayback(bookId, assetId, positionMs, bookTimeMs, at, operationId, baseVersion, contentVersion)

internal fun StoredPlayback.local() =
    LocalPlayback(bookId, assetId, positionMs, bookTimeMs, at, operationId, baseVersion, contentVersion)
