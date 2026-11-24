package app.tfdn.lirelle.core.audio.settings

import androidx.datastore.core.DataStore
import app.tfdn.lirelle.core.ids.BookId
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/** The key the profile's speeds are kept under. */
internal const val BookSpeedsName = "book_speeds"

/** The key the device's player settings are kept under. */
internal const val PlayerSettingsName = "player_settings"

/** The speeds chosen, by book id. */
@Serializable
internal data class StoredBookSpeeds(
    val speeds: Map<String, Float> = emptyMap(),
    val narrationSpeeds: Map<String, Float> = emptyMap(),
)

/** Reads the speeds one by one: one that is not a number is none, and the others are kept. */
internal object TolerantSpeedsSerializer : KSerializer<StoredBookSpeeds> {
    private val plain = StoredBookSpeeds.serializer()

    override val descriptor: SerialDescriptor = plain.descriptor

    override fun serialize(encoder: Encoder, value: StoredBookSpeeds) = encoder.encodeSerializableValue(plain, value)

    override fun deserialize(decoder: Decoder): StoredBookSpeeds {
        val stored = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: return StoredBookSpeeds()
        return StoredBookSpeeds(speeds = stored.speeds("speeds"), narrationSpeeds = stored.speeds("narrationSpeeds"))
    }

    /** The speeds that are numbers, by book. */
    private fun JsonObject.speeds(name: String): Map<String, Float> = (this[name] as? JsonObject).orEmpty()
        .mapNotNull { (book, speed) -> decodeOrNull(Float.serializer(), speed)?.let { book to it } }
        .toMap()
}

/**
 * The speed chosen for each book and for its narration: the profile's half of the player settings. The books
 * are an account's, on its server: another profile has other books under the same ids, so each has its own
 * file, in its folder, and the speeds go with the profile.
 */
internal class BookSpeeds(dataStore: DataStore<Preferences>) {
    private val value = JsonValue(dataStore, BookSpeedsName, TolerantSpeedsSerializer, StoredBookSpeeds())

    suspend fun of(bookId: BookId): Float? = value.read().speeds[bookId.value]?.toValidSpeed()

    /** @param speed within bounds already. */
    suspend fun set(bookId: BookId, speed: Float) =
        value.change { it.copy(speeds = it.speeds + (bookId.value to speed)) }

    suspend fun narrationOf(bookId: BookId): Float? = value.read().narrationSpeeds[bookId.value]?.toValidSpeed()

    /** @param speed within bounds already. */
    suspend fun setNarration(bookId: BookId, speed: Float) =
        value.change { it.copy(narrationSpeeds = it.narrationSpeeds + (bookId.value to speed)) }
}
