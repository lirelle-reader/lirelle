package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.serialization.json.JsonNull

/** The messages of the read-along; null when [type] is none of them. */
internal fun decodeNarrationMessage(type: String, fields: PageFields): PageMessage? = when (type) {
    "narrationFound" -> PageMessage.NarrationFound(
        requestId = requestId(fields),
        sectionIndex = sectionIndex(fields),
        id = fields.optionalText("id", BridgeLimits.MaxElementIdLength),
    )
    "narrationCfiOf" -> narrationCfiOf(fields)
    "narrationVisible" -> PageMessage.NarrationVisible(fields.flag("visible"))
    "narrationMissing" -> PageMessage.NarrationMissing(
        sectionIndex = sectionIndex(fields),
        id = fields.text("id", BridgeLimits.MaxElementIdLength),
    )
    else -> null
}

private fun requestId(fields: PageFields) = fields.integer("requestId", 0..Int.MAX_VALUE)

private fun sectionIndex(fields: PageFields) = fields.integer("sectionIndex", 0 until BridgeLimits.MaxSections)

/** The position of a sentence's start, null for a sentence that is not in its section; how far it is, if known. */
private fun narrationCfiOf(fields: PageFields): PageMessage.NarrationCfiOf {
    val absent = (fields.element("fraction") ?: JsonNull) is JsonNull
    val message = PageMessage.NarrationCfiOf(
        requestId = requestId(fields),
        cfi = fields.optionalText("cfi", BridgeLimits.MaxCfiLength),
        fraction = if (absent) null else fields.number("fraction", UnitRange),
    )
    if (message.cfi != null && !CfiShape.matches(message.cfi)) fields.fail(Unit)
    return message
}
