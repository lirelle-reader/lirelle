package app.tfdn.lirelle.core.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The account's id as the registry stores it: written as text, and read from text or from a whole number — what
 * the versions before wrote, when the only server numbered its accounts — as that number's text. This is how the
 * registry is migrated: in place, each profile written as text at its next write.
 *
 * Anything else (`true`, `1.5`, an object, null) fails as a field of another shape does: the entry is one this
 * version cannot understand, kept as it was written ([UnreadEntry]).
 */
internal object AccountIdSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AccountId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val primitive = json.decodeJsonElement() as? JsonPrimitive
        return when {
            primitive == null || primitive is JsonNull -> null
            primitive.isString || primitive.longOrNull != null -> primitive.content
            else -> null
        } ?: throw SerializationException("An account id is a whole number or text")
    }
}
