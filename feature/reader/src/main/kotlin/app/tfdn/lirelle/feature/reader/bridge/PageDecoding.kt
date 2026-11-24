package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private const val MaxTypeLength = 32

/** The message this object is, or null when it is not exactly one of the contract's. */
internal fun decodePageMessage(root: JsonObject): PageMessage? {
    val fields = PageFields(root)
    val type = fields.text("type", MaxTypeLength)
    val message = when (type) {
        "ready" -> PageMessage.Ready
        "opened" -> opened(fields)
        "relocated" -> relocated(fields)
        "pastEnd" -> PageMessage.PastEnd
        "turning" -> PageMessage.Turning
        "tap" -> PageMessage.Tap(x = fields.number("x", UnitRange), y = fields.number("y", UnitRange))
        "brightness" -> brightness(fields)
        "link" -> PageMessage.Link(fields.text("href", BridgeLimits.MaxHrefLength), fields.flag("external"))
        "error" -> PageMessage.Error(
            code = fields.word("code", PageErrorCode.entries) { it.wire },
            message = fields.text("message", BridgeLimits.MaxErrorMessageLength),
        )
        else -> decodeMarksMessage(type, fields) ?: decodeNarrationMessage(type, fields)
    }
    return message.takeUnless { fields.failed }
}

/**
 * A book must open whatever the length of its title or of its contents: an over-long title or label is cut, an
 * over-long table of contents keeps its first entries. Everything else is as strict as in the other messages.
 */
private fun opened(fields: PageFields) = PageMessage.Opened(
    toc = fields.objects("toc", BridgeLimits.MaxTocEntries, ::tocEntry),
    dir = fields.word("dir", PageDirection.entries) { it.wire },
    fixedLayout = fields.flag("fixedLayout"),
    title = fields.optionalText("title", BridgeLimits.MaxLabelLength, TooLong.IsCut),
    sectionLabels = fields.sectionLabels(),
)

/** An address cannot be cut and still lead somewhere: an entry whose address is too long is left out. */
private fun tocEntry(fields: PageFields) = TocEntry(
    id = fields.integer("id", 0..Int.MAX_VALUE),
    label = fields.text("label", BridgeLimits.MaxLabelLength, TooLong.IsCut),
    href = fields.text("href", BridgeLimits.MaxHrefLength, TooLong.LeavesOut),
    depth = fields.integer("depth", 0..BridgeLimits.MaxTocDepth),
)

private fun relocated(fields: PageFields): PageMessage? {
    val sectionCount = fields.integer("sectionCount", 1..BridgeLimits.MaxSections)
    val message = PageMessage.Relocated(
        cfi = fields.text("cfi", BridgeLimits.MaxCfiLength),
        fraction = fields.number("fraction", UnitRange),
        sectionIndex = fields.integer("sectionIndex", 0 until sectionCount),
        sectionCount = sectionCount,
        page = fields.optionalInteger("page", 0..BridgeLimits.MaxPages),
        pages = fields.optionalInteger("pages", 0..BridgeLimits.MaxPages),
        tocId = fields.optionalInteger("tocId", 0..Int.MAX_VALUE),
        tocLabel = fields.optionalText("tocLabel", BridgeLimits.MaxLabelLength, TooLong.IsCut),
        atEnd = fields.flag("atEnd"),
        moved = fields.flag("moved"),
        bookmarkIds = fields.bookmarkIds(),
        byHost = fields.optionalFlag("byHost"),
        byNarration = fields.optionalFlag("byNarration"),
        // A page from before the flag tells no layout from a move: each move is the user's, as it always was.
        userMove = (fields.element("userMove") ?: JsonNull) is JsonNull || fields.flag("userMove"),
    )
    return message.takeIf { CfiShape.matches(it.cfi) }
}

/** Null, or absent, is false: a page from before the flag never sends it. */
private fun PageFields.optionalFlag(name: String): Boolean = (element(name) ?: JsonNull) !is JsonNull && flag(name)

private fun brightness(fields: PageFields) = PageMessage.Brightness(
    phase = fields.word("phase", BrightnessPhase.entries) { it.wire },
    delta = fields.number("delta", -BridgeLimits.MaxBrightnessDelta..BridgeLimits.MaxBrightnessDelta),
)
