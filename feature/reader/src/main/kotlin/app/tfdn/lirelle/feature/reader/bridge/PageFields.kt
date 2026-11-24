package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MaxWordLength = 32

/** The shape of a CFI: whether it leads anywhere is for the page to say. */
internal val CfiShape = Regex("""epubcfi\(.+\)""")

/** Fractions of a page. */
internal val UnitRange = 0.0..1.0

private fun JsonObject.primitive(name: String): JsonPrimitive? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** What a string longer than its limit does to the object that holds it. */
internal enum class TooLong {
    /** The object is [PageFields.failed]. */
    Fails,

    /** The string is cut to the limit, between two characters. */
    IsCut,

    /** The object is [PageFields.leftOut] of the list it belongs to. */
    LeavesOut,
}

/** The first [maxLength] characters, one less when that would keep only the first half of a surrogate pair. */
internal fun String.cutTo(maxLength: Int): String {
    if (length <= maxLength) return this
    val end = if (maxLength > 0 && this[maxLength - 1].isHighSurrogate()) maxLength - 1 else maxLength
    return substring(0, end)
}

/**
 * Reads the fields of one object sent by the page. A field that is missing, of the wrong kind or out of bounds
 * makes the whole object [failed]; the reader then returns a placeholder so that reading can go on to the end.
 */
internal class PageFields(private val fields: JsonObject) {
    var failed = false
        private set

    /** A well-formed entry of a list that is skipped, without failing the list. */
    var leftOut = false
        private set

    /** A JSON string of at most [maxLength] characters; [tooLong] says what a longer one does. */
    fun text(name: String, maxLength: Int, tooLong: TooLong = TooLong.Fails): String {
        val text = fields.primitive(name)?.takeIf { it.isString }?.content ?: return fail("")
        if (text.length <= maxLength) return text
        return when (tooLong) {
            TooLong.Fails -> fail("")
            TooLong.IsCut -> text.cutTo(maxLength)
            TooLong.LeavesOut -> "".also { leftOut = true }
        }
    }

    /** A JSON boolean, not a word or a number that looks like one. */
    fun flag(name: String): Boolean = when (fields.primitive(name)?.takeIf { !it.isString }?.content) {
        "true" -> true
        "false" -> false
        else -> fail(false)
    }

    /** A finite JSON number within [range]. */
    fun number(name: String, range: ClosedFloatingPointRange<Double>): Double =
        fields.primitive(name)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in range } ?: fail(0.0)

    /** A JSON number without a fractional part, within [range]. */
    fun integer(name: String, range: IntRange): Int {
        if (range.isEmpty()) return fail(0)
        val value = number(name, range.first.toDouble()..range.last.toDouble())
        return if (value.toInt().toDouble() == value) value.toInt() else fail(0)
    }

    /** One of a fixed set of words. */
    fun <T> word(name: String, values: List<T>, wire: (T) -> String): T {
        val word = text(name, MaxWordLength)
        return values.firstOrNull { wire(it) == word } ?: fail(values.first())
    }

    /** Null, or absent, or as [text]. */
    fun optionalText(name: String, maxLength: Int, tooLong: TooLong = TooLong.Fails): String? =
        if ((fields[name] ?: JsonNull) is JsonNull) null else text(name, maxLength, tooLong)

    /** Null, or absent, or as [integer]. */
    fun optionalInteger(name: String, range: IntRange): Int? =
        if ((fields[name] ?: JsonNull) is JsonNull) null else integer(name, range)

    /**
     * A JSON array of objects, each read by [read]. Only the first [maxSize] are read: the rest is dropped. An
     * object that fails makes the whole array fail; one that is [leftOut] is only skipped.
     */
    fun <T : Any> objects(name: String, maxSize: Int, read: (PageFields) -> T): List<T> {
        val items = fields[name] as? JsonArray ?: return fail(emptyList())
        return items.take(maxSize).mapNotNull { item ->
            val reader = (item as? JsonObject)?.let(::PageFields) ?: return@mapNotNull fail(null)
            val value = read(reader)
            when {
                reader.failed -> fail(null)
                reader.leftOut -> null
                else -> value
            }
        }
    }

    /** The raw value of a field, for the readers of kinds this class does not know. */
    fun element(name: String): JsonElement? = fields[name]

    fun <T> fail(placeholder: T): T {
        failed = true
        return placeholder
    }
}
