package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bounds on what the page may send. A message beyond one of them is dropped whole, but for a label, a title or
 * the number of entries of the table of contents, which are cut to their bound so that the book still opens.
 */
object BridgeLimits {
    /** Characters of one message, checked before it is parsed. */
    const val MaxMessageLength = 4_000_000

    /** Entries of the table of contents that are kept: the first ones. */
    const val MaxTocEntries = 10_000
    const val MaxTocDepth = 64

    /** A label of the table of contents, the current chapter's label, the book's title: a longer one is cut. */
    const val MaxLabelLength = 2_000

    const val MaxHrefLength = 8_192
    const val MaxCfiLength = 2_048
    const val MaxErrorMessageLength = 1_000
    const val MaxSections = 100_000

    /** Pages of one section. */
    const val MaxPages = 1_000_000

    /** A brightness step, as a fraction of the screen height, either way. */
    const val MaxBrightnessDelta = 1.0

    /** The text of a selection: a longer one is cut and reported as truncated. */
    const val MaxSelectionText = 10_000

    /** The id of a mark, the app's local id. */
    const val MaxIdLength = 64
    const val MaxHighlightIds = 32
    const val MaxBookmarkIds = 64

    /** Hits of one `searchResults` message, in all its groups: the first ones. */
    const val MaxSearchHits = 100

    /** Text before and after a hit, and the hit itself: a longer one is cut. */
    const val MaxHitContext = 200
    const val MaxHitMatch = 400

    /** The id of an element of a book, as a sentence of a narration has one. */
    const val MaxElementIdLength = 1_024

    /** Narrated ids sent in one `narrationFind`: the first ones. */
    const val MaxNarrationIds = 5_000
}

/**
 * The two directions of the bridge. The page shows a book's content, so what it sends is not trusted:
 * [decode] gives null for anything that is not exactly a message of the contract, within [BridgeLimits].
 */
object BridgeCodec {
    private const val Receiver = "window.lirelle.receive"

    /**
     * Null for malformed JSON, an unknown type, a missing or ill-typed field, a value out of bounds other than the
     * ones [BridgeLimits] says are cut. Never throws.
     */
    fun decode(json: String): PageMessage? {
        if (json.length > BridgeLimits.MaxMessageLength) return null
        val root = try {
            Json.parseToJsonElement(json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return (root as? JsonObject)?.let(::decodePageMessage)
    }

    fun encode(message: HostMessage): String = buildJsonObject {
        put("type", message.wire)
        when (message) {
            is HostMessage.Open -> {
                put("base", message.base)
                put("cfi", message.cfi)
                put("fraction", finite(message.fraction))
                put("format", message.format.wire)
                put("settings", settings(message.settings))
            }
            is HostMessage.GoTo -> target(message)
            is HostMessage.ApplySettings -> put("settings", settings(message.settings))
            is HostMessage.SetHighlights -> put("items", JsonArray(message.items.map(::highlight)))
            is HostMessage.PutHighlight -> put("item", highlight(message.item))
            is HostMessage.RemoveHighlight -> put("id", message.id)
            is HostMessage.SetBookmarks -> put("items", JsonArray(message.items.map(::bookmark)))
            is HostMessage.Search -> {
                put("searchId", message.searchId)
                put("query", message.query)
            }
            is HostMessage.ShowSearchResult -> put("cfi", message.cfi)
            is HostMessage.NarrationMark -> narrationMark(message)
            is HostMessage.NarrationFind -> narrationFind(message)
            is HostMessage.NarrationCfi -> {
                put("requestId", message.requestId)
                put("sectionIndex", message.sectionIndex)
                put("id", message.id)
            }
            HostMessage.Next,
            HostMessage.Prev,
            HostMessage.ClearSelection,
            HostMessage.CancelSearch,
            HostMessage.ClearSearch,
            HostMessage.CloseNote,
            HostMessage.NarrationClear,
            -> Unit
        }
    }.toString()

    private fun highlight(item: PageHighlight) = buildJsonObject {
        put("id", item.id)
        put("cfi", item.cfi)
        put("color", item.color)
        put("style", item.style.wire)
        put("note", item.note)
    }

    private fun JsonObjectBuilder.narrationMark(message: HostMessage.NarrationMark) {
        put("sectionIndex", message.sectionIndex)
        put("id", message.id)
        put("paused", message.paused)
        put("follow", message.follow)
    }

    private fun JsonObjectBuilder.narrationFind(message: HostMessage.NarrationFind) {
        put("requestId", message.requestId)
        put("sectionIndex", message.sectionIndex)
        put("ids", JsonArray(message.ids.take(BridgeLimits.MaxNarrationIds).map(::JsonPrimitive)))
        put("what", message.what.wire)
        message.prefer?.let { put("prefer", it) }
    }

    private fun bookmark(item: PageBookmark) = buildJsonObject {
        put("id", item.id)
        put("cfi", item.cfi)
    }

    /**
     * The script that hands [message] to the page: the JSON goes in as one string literal, itself encoded as a JSON
     * string, with the few characters that mean something to a script or a document written as escapes. Whatever a
     * message holds, it stays inside the literal.
     */
    fun receiveCall(message: HostMessage): String {
        val literal = JsonPrimitive(encode(message)).toString()
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
        return "$Receiver($literal)"
    }

    private fun JsonObjectBuilder.target(message: HostMessage.GoTo) = when (message) {
        is HostMessage.GoTo.Cfi -> put("cfi", message.cfi)
        is HostMessage.GoTo.Href -> put("href", message.href)
        is HostMessage.GoTo.Fraction -> put("fraction", finite(message.fraction))
    }

    private fun settings(settings: PageSettings) = buildJsonObject {
        put("background", settings.background)
        put("foreground", settings.foreground)
        put("link", settings.link)
        put("dark", settings.dark)
        put("font", settings.font.wire)
        put("fontSizePx", settings.fontSizePx)
        put("lineHeight", finite(settings.lineHeight))
        put("marginPercent", finite(settings.marginPercent))
        put("align", settings.align.wire)
        put("hyphenate", settings.hyphenate)
        put("flow", settings.flow.wire)
        put("maxColumns", settings.maxColumns)
        put("brightnessEdge", settings.brightnessEdge.wire)
        put("highlightBlend", settings.highlightBlend.wire)
        put("highlightOpacity", finite(settings.highlightOpacity))
        put("searchColor", settings.searchColor)
        put("footnotePanel", settings.footnotePanel)
        put(
            "noteLabels",
            buildJsonObject {
                put("goTo", settings.noteLabels.goTo)
                put("title", settings.noteLabels.title)
            },
        )
    }

    /** JSON has no word for a number that is not finite: the page gets null and falls back. */
    private fun finite(value: Double?) = if (value != null && value.isFinite()) JsonPrimitive(value) else JsonNull
}
