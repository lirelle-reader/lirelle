package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An instant as ISO-8601 text, so that it comes back exactly as it was written. */
internal object InstantAsText : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = try {
        Instant.parse(decoder.decodeString())
    } catch (failure: DateTimeParseException) {
        throw SerializationException("Not an instant", failure)
    }
}

/** [page] came later: a position stored before reads with none, and one without a page is written as before. */
@Serializable
internal data class StoredPosition(
    val fileId: FileId,
    val cfi: String? = null,
    val percent: Double,
    @Serializable(InstantAsText::class) val at: Instant,
    val page: Int? = null,
)

@Serializable
internal data class StoredNarration(
    val fileId: FileId,
    val seconds: Double,
    val fragment: String,
    val sectionIndex: Int,
    val percent: Double,
    @Serializable(InstantAsText::class) val at: Instant,
)

@Serializable
internal data class StoredSession(
    val fileId: FileId,
    val sessionId: String,
    @Serializable(InstantAsText::class) val startedAt: Instant,
    @Serializable(InstantAsText::class) val endedAt: Instant,
    val activeSeconds: Int,
    val progressDelta: Double? = null,
    val endPercent: Double? = null,
)

/**
 * What waits to be sent. [narrations] came later: an outbox stored before reads with none. As for the
 * positions, an `owner` written before the profiles is not read.
 */
@Serializable
internal data class StoredOutbox(
    val positions: List<StoredPosition> = emptyList(),
    val sessions: List<StoredSession> = emptyList(),
    val narrations: List<StoredNarration> = emptyList(),
)

/**
 * The positions kept on this device. A value written before the profiles also names an `owner`, which is no
 * longer read: the file is in a profile's folder, and holds nobody else's.
 */
@Serializable
internal data class StoredPositions(val positions: List<StoredPosition> = emptyList())

/** The narrations kept on this device. As for the positions, an `owner` written before the profiles is not read. */
@Serializable
internal data class StoredNarrations(val narrations: List<StoredNarration> = emptyList())

internal fun LocalNarration.stored() =
    StoredNarration(fileId, place.seconds, place.fragment, place.sectionIndex, percent, at)

internal fun StoredNarration.narration() =
    LocalNarration(fileId, NarrationPlace(seconds, fragment, sectionIndex), percent, at)

internal fun LocalPosition.stored() = StoredPosition(fileId, cfi, percent, at, page)

internal fun StoredPosition.position() = LocalPosition(fileId, cfi, percent, at, page)

internal fun PendingSession.stored() = StoredSession(
    fileId = fileId,
    sessionId = session.sessionId,
    startedAt = session.startedAt,
    endedAt = session.endedAt,
    activeSeconds = session.activeSeconds,
    progressDelta = session.progressDelta,
    endPercent = session.endPercent,
)

internal fun StoredSession.pending() = PendingSession(
    fileId,
    ReadingSessionReport(sessionId, startedAt, endedAt, activeSeconds, progressDelta, endPercent),
)
