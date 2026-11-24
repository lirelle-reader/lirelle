package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The messages of selections, highlights, search and notes; null when [type] is none of them. */
internal fun decodeMarksMessage(type: String, fields: PageFields): PageMessage? = when (type) {
    "selection" -> selection(fields)
    "selectionCleared" -> PageMessage.SelectionCleared
    "highlightTap" -> PageMessage.HighlightTap(
        id = fields.text("id", BridgeLimits.MaxIdLength),
        bounds = fields.bounds("bounds"),
    )
    "searchResults" -> searchResults(fields)
    "searchProgress" -> PageMessage.SearchProgress(searchId(fields), fields.number("fraction", UnitRange))
    "searchDone" -> PageMessage.SearchDone(
        searchId = searchId(fields),
        total = fields.integer("total", 0..Int.MAX_VALUE),
        truncated = fields.flag("truncated"),
    )
    "noteOpened" -> PageMessage.NoteOpened(
        href = fields.text("href", BridgeLimits.MaxHrefLength),
        label = fields.optionalText("label", BridgeLimits.MaxLabelLength, TooLong.IsCut),
    )
    "noteClosed" -> PageMessage.NoteClosed
    else -> null
}

/** The contents labels of the sections; an `opened` from before they existed has none. */
internal fun PageFields.sectionLabels(): List<String?> =
    texts("sectionLabels", BridgeLimits.MaxSections, BridgeLimits.MaxLabelLength, Items.Labels)

/** The bookmarks of the visible page; a `relocated` from before they existed has none. */
internal fun PageFields.bookmarkIds(): List<String> =
    texts("bookmarkIds", BridgeLimits.MaxBookmarkIds, BridgeLimits.MaxIdLength, Items.Ids).filterNotNull()

private fun searchId(fields: PageFields) = fields.integer("searchId", 0..Int.MAX_VALUE)

private fun selection(fields: PageFields): PageMessage? {
    val raw = fields.text("text", BridgeLimits.MaxMessageLength)
    val message = PageMessage.Selection(
        cfi = fields.text("cfi", BridgeLimits.MaxCfiLength),
        text = raw.cutTo(BridgeLimits.MaxSelectionText),
        truncated = fields.flag("truncated") || raw.length > BridgeLimits.MaxSelectionText,
        sectionIndex = fields.integer("sectionIndex", 0 until BridgeLimits.MaxSections),
        bounds = fields.bounds("bounds"),
        highlightIds = fields.texts(
            "highlightIds", BridgeLimits.MaxHighlightIds, BridgeLimits.MaxIdLength, Items.RequiredIds,
        ).filterNotNull(),
        exactId = fields.optionalText("exactId", BridgeLimits.MaxIdLength),
    )
    return message.takeIf { CfiShape.matches(it.cfi) }
}

/** The hits are counted across the groups: the first [BridgeLimits.MaxSearchHits] are read, the rest dropped. */
private fun searchResults(fields: PageFields): PageMessage.SearchResults {
    val searchId = searchId(fields)
    var budget = BridgeLimits.MaxSearchHits
    val groups = fields.objects("groups", BridgeLimits.MaxSearchHits) { group ->
        searchGroup(group, budget).also { budget -= it.results.size }
    }
    // Absent from a page of before it existed.
    val absent = (fields.element("fraction") ?: JsonNull) is JsonNull
    val fraction = if (absent) null else fields.number("fraction", UnitRange)
    return PageMessage.SearchResults(searchId, groups.filter { it.results.isNotEmpty() }, fraction)
}

private fun searchGroup(fields: PageFields, budget: Int) = SearchGroup(
    sectionIndex = fields.integer("sectionIndex", 0 until BridgeLimits.MaxSections),
    label = fields.optionalText("label", BridgeLimits.MaxLabelLength, TooLong.IsCut),
    results = if (budget > 0) fields.objects("results", budget, ::searchHit) else emptyList(),
)

private fun searchHit(fields: PageFields): SearchHit {
    val hit = SearchHit(
        cfi = fields.text("cfi", BridgeLimits.MaxCfiLength),
        pre = fields.text("pre", BridgeLimits.MaxHitContext, TooLong.IsCut),
        match = fields.text("match", BridgeLimits.MaxHitMatch, TooLong.IsCut),
        post = fields.text("post", BridgeLimits.MaxHitContext, TooLong.IsCut),
    )
    if (!CfiShape.matches(hit.cfi)) fields.fail(Unit)
    return hit
}

/** A rectangle within the page, not turned inside out. */
private fun PageFields.bounds(name: String): PageBounds {
    val inner = (element(name) as? JsonObject)?.let(::PageFields) ?: return fail(PageBounds(0.0, 0.0, 0.0, 0.0))
    val bounds = PageBounds(
        left = inner.number("left", UnitRange),
        top = inner.number("top", UnitRange),
        right = inner.number("right", UnitRange),
        bottom = inner.number("bottom", UnitRange),
    )
    val inverted = bounds.left > bounds.right || bounds.top > bounds.bottom
    return if (inner.failed || inverted) fail(bounds) else bounds
}

/** What a list of strings accepts. */
private enum class Items(val required: Boolean, val nullable: Boolean, val tooLong: TooLong) {
    /** Ids: the list must be there, and an id that is too long fails the message. */
    RequiredIds(required = true, nullable = false, tooLong = TooLong.Fails),

    /** Ids of a message that did not always carry them: a missing list is empty. */
    Ids(required = false, nullable = false, tooLong = TooLong.Fails),

    /** Labels, which may be null and are cut. */
    Labels(required = false, nullable = true, tooLong = TooLong.IsCut),
}

/**
 * A JSON array of strings: the first [maxSize] are read. A missing array is empty, or fails when the kind requires
 * it; an element of another kind, or a null that is not allowed, fails the object.
 */
private fun PageFields.texts(name: String, maxSize: Int, maxLength: Int, kind: Items): List<String?> {
    val items = when (val array = element(name)) {
        null, JsonNull -> return if (kind.required) fail(emptyList()) else emptyList()
        is JsonArray -> array
        else -> return fail(emptyList())
    }
    return items.take(maxSize).map { item ->
        val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            text == null && kind.nullable && item is JsonNull -> null
            text == null -> fail(null)
            text.length <= maxLength -> text
            kind.tooLong == TooLong.IsCut -> text.cutTo(maxLength)
            else -> fail(null)
        }
    }
}
