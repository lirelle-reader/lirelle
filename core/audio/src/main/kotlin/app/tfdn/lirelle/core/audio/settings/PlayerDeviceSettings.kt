package app.tfdn.lirelle.core.audio.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The player's settings that are the device's: the skips, the resume, the sleep timer and the speed a new book
 * starts at. The same for every profile, and kept when a profile goes: nothing of an account is in them, and
 * the interface offers no way to clear them.
 */
interface PlayerDeviceSettings {
    /** Always within bounds. */
    val settings: Flow<PlayerSettings>

    /** Applies [t] to what is stored; the result is stored within bounds. */
    suspend fun update(t: (PlayerSettings) -> PlayerSettings)
}

private const val ChapterName = "chapter"
private const val MinutesPrefix = "minutes:"

/** Every field is optional: what cannot be read is the default. */
@Serializable
internal data class StoredPlayerSettings(
    val backSeconds: Int? = null,
    val forwardSeconds: Int? = null,
    val rewindOnResume: Boolean? = null,
    val sleepFade: Boolean? = null,
    val shakeToExtend: Boolean? = null,
    val lastSleepChoice: String? = null,
    val lastSpeed: Float? = null,
)

private fun SleepChoice.name(): String = when (this) {
    is SleepChoice.Minutes -> "$MinutesPrefix$minutes"
    SleepChoice.EndOfChapter -> ChapterName
}

private fun String?.sleepChoice(): SleepChoice? = when {
    this == null -> null
    this == ChapterName -> SleepChoice.EndOfChapter
    startsWith(MinutesPrefix) -> removePrefix(MinutesPrefix).toIntOrNull()?.takeIf { it > 0 }?.let(SleepChoice::Minutes)
    else -> null
}

internal fun StoredPlayerSettings.settings(): PlayerSettings {
    val default = PlayerSettings()
    return PlayerSettings(
        backSeconds = backSeconds ?: default.backSeconds,
        forwardSeconds = forwardSeconds ?: default.forwardSeconds,
        rewindOnResume = rewindOnResume ?: default.rewindOnResume,
        sleepFade = sleepFade ?: default.sleepFade,
        shakeToExtend = shakeToExtend ?: default.shakeToExtend,
        lastSleepChoice = lastSleepChoice.sleepChoice(),
        lastSpeed = lastSpeed ?: default.lastSpeed,
    ).normalized()
}

private fun StoredPlayerSettings.with(settings: PlayerSettings) = copy(
    backSeconds = settings.backSeconds,
    forwardSeconds = settings.forwardSeconds,
    rewindOnResume = settings.rewindOnResume,
    sleepFade = settings.sleepFade,
    shakeToExtend = settings.shakeToExtend,
    lastSleepChoice = settings.lastSleepChoice?.name(),
    lastSpeed = settings.lastSpeed,
)

/** [element] as [serializer] reads it; null when it is of another type. */
internal fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? = try {
    Json.decodeFromJsonElement(serializer, element)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Reads the stored object field by field: a field of the wrong type is the default, and the rest is kept. What
 * the object holds besides — the book speeds an installation from before the profiles kept here — is not read.
 */
internal object TolerantStoredSerializer : KSerializer<StoredPlayerSettings> {
    private val plain = StoredPlayerSettings.serializer()

    override val descriptor: SerialDescriptor = plain.descriptor

    override fun serialize(encoder: Encoder, value: StoredPlayerSettings) =
        encoder.encodeSerializableValue(plain, value)

    override fun deserialize(decoder: Decoder): StoredPlayerSettings {
        val stored = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: return StoredPlayerSettings()
        return StoredPlayerSettings(
            backSeconds = stored.field("backSeconds", Int.serializer()),
            forwardSeconds = stored.field("forwardSeconds", Int.serializer()),
            rewindOnResume = stored.field("rewindOnResume", Boolean.serializer()),
            sleepFade = stored.field("sleepFade", Boolean.serializer()),
            shakeToExtend = stored.field("shakeToExtend", Boolean.serializer()),
            lastSleepChoice = stored.field("lastSleepChoice", String.serializer()),
            lastSpeed = stored.field("lastSpeed", Float.serializer()),
        )
    }

    private fun <T> JsonObject.field(name: String, serializer: KSerializer<T>): T? =
        this[name]?.let { decodeOrNull(serializer, it) }
}

/** The device's half of the player settings, in a file of the device: one for all the profiles. */
class DataStorePlayerDeviceSettings(dataStore: DataStore<Preferences>) : PlayerDeviceSettings {
    private val value =
        JsonValue(dataStore, PlayerSettingsName, TolerantStoredSerializer, StoredPlayerSettings())

    override val settings: Flow<PlayerSettings> = value.flow.map { it.settings() }

    override suspend fun update(t: (PlayerSettings) -> PlayerSettings) =
        value.change { it.with(t(it.settings()).normalized()) }
}
