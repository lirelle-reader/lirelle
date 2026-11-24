package app.tfdn.lirelle.feature.reader.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

private const val Cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)"

private const val Bounds = """{"left":0.1,"top":0.2,"right":0.9,"bottom":0.3}"""

private val PageBox = PageBounds(0.1, 0.2, 0.9, 0.3)

private fun selectionJson(
    text: String = "\"hello\"",
    bounds: String = Bounds,
    ids: String = "[\"a\",\"b\"]",
    cfi: String = Cfi,
) = """{"type":"selection","cfi":"$cfi","text":$text,"truncated":false,"sectionIndex":3,"bounds":$bounds,
    "highlightIds":$ids,"exactId":"a"}"""

private fun hitJson(cfi: String = Cfi, match: String = "m") =
    """{"cfi":"$cfi","pre":"p","match":"$match","post":"q"}"""

private fun groupJson(hits: String, sectionIndex: Int = 1, label: String = "\"One\"") =
    """{"sectionIndex":$sectionIndex,"label":$label,"results":[$hits]}"""

private fun resultsJson(vararg groups: String) =
    """{"type":"searchResults","searchId":2,"groups":[${groups.joinToString(",")}]}"""

private fun hits(count: Int, from: Int = 1) = (from until from + count).joinToString(",") { hitJson(match = "m$it") }

private fun encoded(message: HostMessage): JsonElement = Json.parseToJsonElement(BridgeCodec.encode(message))

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

class BridgeMarksDecodeTest {

    @Test
    fun `a selection is decoded`() {
        assertThat(BridgeCodec.decode(selectionJson())).isEqualTo(
            PageMessage.Selection(Cfi, "hello", false, 3, PageBox, listOf("a", "b"), "a"),
        )
    }

    @Test
    fun `a selection without an exact highlight has a null id`() {
        val text = selectionJson().replace(""""exactId":"a"""", """"exactId":null""")

        assertThat((BridgeCodec.decode(text) as PageMessage.Selection).exactId).isNull()
    }

    @Test
    fun `a text over 10000 is cut and reported as truncated`() {
        val long = "x".repeat(BridgeLimits.MaxSelectionText + 5)

        val message = BridgeCodec.decode(selectionJson(text = "\"$long\"")) as PageMessage.Selection

        assertThat(message.text).hasLength(BridgeLimits.MaxSelectionText)
        assertThat(message.truncated).isTrue()
    }

    @Test
    fun `a text at the limit is kept whole`() {
        val text = "x".repeat(BridgeLimits.MaxSelectionText)

        val message = BridgeCodec.decode(selectionJson(text = "\"$text\"")) as PageMessage.Selection

        assertThat(message.text).isEqualTo(text)
        assertThat(message.truncated).isFalse()
    }

    @Test
    fun `bounds outside 0 to 1 or inverted are refused`() {
        listOf(
            """{"left":-0.1,"top":0.2,"right":0.9,"bottom":0.3}""",
            """{"left":0.1,"top":0.2,"right":1.5,"bottom":0.3}""",
            """{"left":0.9,"top":0.2,"right":0.1,"bottom":0.3}""",
            """{"left":0.1,"top":0.5,"right":0.9,"bottom":0.3}""",
            """{"left":0.1,"top":0.2,"right":0.9}""",
            "null",
        ).forEach { assertThat(BridgeCodec.decode(selectionJson(bounds = it))).isNull() }
    }

    @Test
    fun `an id over 64 characters fails the message`() {
        val long = "i".repeat(BridgeLimits.MaxIdLength + 1)

        assertThat(BridgeCodec.decode(selectionJson(ids = "[\"$long\"]"))).isNull()
        assertThat(BridgeCodec.decode("""{"type":"highlightTap","id":"$long","bounds":$Bounds}""")).isNull()
        assertThat(BridgeCodec.decode(selectionJson().replace(""""exactId":"a"""", """"exactId":"$long""""))).isNull()
    }

    @Test
    fun `highlightIds is required, and holds strings only`() {
        val without = selectionJson().replace(""""highlightIds":["a","b"],""", "")

        assertThat(BridgeCodec.decode(without)).isNull()
        assertThat(BridgeCodec.decode(selectionJson(ids = "null"))).isNull()
        assertThat(BridgeCodec.decode(selectionJson(ids = "[null]"))).isNull()
        assertThat(BridgeCodec.decode(selectionJson(ids = "[3]"))).isNull()
        assertThat(BridgeCodec.decode(selectionJson(ids = "[]"))).isNotNull()
    }

    @Test
    fun `a section index out of range fails a selection and search results`() {
        val last = BridgeLimits.MaxSections
        val selection = selectionJson().replace(""""sectionIndex":3""", """"sectionIndex":$last""")
        val results = resultsJson(groupJson(hitJson(), last))

        assertThat(BridgeCodec.decode(selection)).isNull()
        assertThat(BridgeCodec.decode(results)).isNull()
        assertThat(BridgeCodec.decode(selection.replace("$last", "-1"))).isNull()
    }

    @Test
    fun `a page-sent truncated flag stays true on a short text`() {
        val text = selectionJson().replace(""""truncated":false""", """"truncated":true""")

        assertThat((BridgeCodec.decode(text) as PageMessage.Selection).truncated).isTrue()
    }

    @Test
    fun `a tap on a highlight with bad bounds is refused`() {
        listOf(
            """{"left":0.9,"top":0.2,"right":0.1,"bottom":0.3}""",
            """{"left":0.1,"top":0.2,"right":0.9,"bottom":2}""",
            "{}",
        ).forEach {
            assertThat(BridgeCodec.decode("""{"type":"highlightTap","id":"h","bounds":$it}""")).isNull()
        }
    }

    @Test
    fun `an over-long note address or a negative total fails`() {
        val long = "h".repeat(BridgeLimits.MaxHrefLength + 1)

        assertThat(BridgeCodec.decode("""{"type":"noteOpened","href":"$long","label":null}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"searchDone","searchId":1,"total":-1,"truncated":false}""")).isNull()
    }

    @Test
    fun `an id at 64 characters is kept`() {
        val id = "i".repeat(BridgeLimits.MaxIdLength)

        val message = BridgeCodec.decode("""{"type":"highlightTap","id":"$id","bounds":$Bounds}""")

        assertThat(message).isEqualTo(PageMessage.HighlightTap(id, PageBox))
    }

    @Test
    fun `highlight ids over 32 are cut`() {
        val ids = (1..40).joinToString(",", "[", "]") { "\"h$it\"" }

        val message = BridgeCodec.decode(selectionJson(ids = ids)) as PageMessage.Selection

        assertThat(message.highlightIds).hasSize(BridgeLimits.MaxHighlightIds)
        assertThat(message.highlightIds.first()).isEqualTo("h1")
    }

    @Test
    fun `a malformed CFI fails a selection and a hit`() {
        assertThat(BridgeCodec.decode(selectionJson(cfi = "nonsense"))).isNull()
        assertThat(BridgeCodec.decode(resultsJson(groupJson(hitJson(cfi = "nonsense"))))).isNull()
    }

    @Test
    fun `selectionCleared, searchProgress, searchDone and the note messages are decoded`() {
        assertThat(BridgeCodec.decode("""{"type":"selectionCleared"}""")).isEqualTo(PageMessage.SelectionCleared)
        assertThat(BridgeCodec.decode("""{"type":"searchProgress","searchId":4,"fraction":0.5}"""))
            .isEqualTo(PageMessage.SearchProgress(4, 0.5))
        assertThat(BridgeCodec.decode("""{"type":"searchDone","searchId":4,"total":12,"truncated":true}"""))
            .isEqualTo(PageMessage.SearchDone(4, 12, true))
        assertThat(BridgeCodec.decode("""{"type":"noteOpened","href":"n.xhtml#a","label":"3"}"""))
            .isEqualTo(PageMessage.NoteOpened("n.xhtml#a", "3"))
        assertThat(BridgeCodec.decode("""{"type":"noteOpened","href":"n.xhtml#a","label":null}"""))
            .isEqualTo(PageMessage.NoteOpened("n.xhtml#a", null))
        assertThat(BridgeCodec.decode("""{"type":"noteClosed"}""")).isEqualTo(PageMessage.NoteClosed)
    }

    @Test
    fun `a negative search id or a progress out of 0 to 1 fails`() {
        assertThat(BridgeCodec.decode("""{"type":"searchProgress","searchId":-1,"fraction":0.5}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"searchProgress","searchId":1,"fraction":1.5}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"searchDone","searchId":-1,"total":0,"truncated":false}""")).isNull()
        assertThat(BridgeCodec.decode(resultsJson().replace(""""searchId":2""", """"searchId":-2"""))).isNull()
    }

    @Test
    fun `search results are decoded, grouped by section`() {
        val hit = SearchHit(Cfi, "p", "m", "q")

        val message = BridgeCodec.decode(resultsJson(groupJson(hitJson()), groupJson(hitJson(), 4, "null")))

        assertThat(message).isEqualTo(
            PageMessage.SearchResults(2, listOf(SearchGroup(1, "One", listOf(hit)), SearchGroup(4, null, listOf(hit)))),
        )
    }

    @Test
    fun `search results carry how far the search has got, when the page says`() {
        val json = resultsJson(groupJson(hitJson())).replace(""""groups"""", """"fraction":0.25,"groups"""")

        assertThat((BridgeCodec.decode(json) as PageMessage.SearchResults).fraction).isEqualTo(0.25)
        assertThat((BridgeCodec.decode(resultsJson(groupJson(hitJson()))) as PageMessage.SearchResults).fraction)
            .isNull()
        assertThat(
            (BridgeCodec.decode(json.replace("0.25", "null")) as PageMessage.SearchResults).fraction,
        ).isNull()
        assertThat(BridgeCodec.decode(json.replace("0.25", "1.5"))).isNull()
        assertThat(BridgeCodec.decode(json.replace("0.25", "-0.1"))).isNull()
        assertThat(BridgeCodec.decode(json.replace("0.25", "\"0.5\""))).isNull()
    }

    @Test
    fun `groups without hits are left out`() {
        val message = BridgeCodec.decode(resultsJson(groupJson(""), groupJson(hitJson(), 3), groupJson("", 5)))

        assertThat((message as PageMessage.SearchResults).groups.map { it.sectionIndex }).containsExactly(3)
        assertThat(BridgeCodec.decode(resultsJson())).isEqualTo(PageMessage.SearchResults(2, emptyList()))
    }

    @Test
    fun `more than 100 hits in one group keep the first 100`() {
        val message = BridgeCodec.decode(resultsJson(groupJson(hits(120)))) as PageMessage.SearchResults

        assertThat(message.groups.single().results).hasSize(BridgeLimits.MaxSearchHits)
        assertThat(message.groups.single().results.first().match).isEqualTo("m1")
    }

    @Test
    fun `the 100 hits are counted across the groups`() {
        val json = resultsJson(groupJson(hits(60), 1), groupJson(hits(60, 61), 2), groupJson(hits(5, 121), 3))

        val message = BridgeCodec.decode(json) as PageMessage.SearchResults

        assertThat(message.groups.map { it.sectionIndex to it.results.size })
            .containsExactly(1 to 60, 2 to 40).inOrder()
        assertThat(message.groups.last().results.last().match).isEqualTo("m100")
    }

    @Test
    fun `a group outside the sections that can exist, or without its fields, fails the message`() {
        assertThat(BridgeCodec.decode(resultsJson(groupJson(hitJson(), -1)))).isNull()
        assertThat(BridgeCodec.decode(resultsJson(groupJson(hitJson(), BridgeLimits.MaxSections)))).isNull()
        assertThat(BridgeCodec.decode(resultsJson("""{"label":null,"results":[${hitJson()}]}"""))).isNull()
        assertThat(BridgeCodec.decode(resultsJson(groupJson(hitJson(cfi = "nope"))))).isNull()
        assertThat(BridgeCodec.decode("""{"type":"searchResults","searchId":2,"groups":{}}""")).isNull()
        assertThat(
            BridgeCodec.decode("""{"type":"searchResults","searchId":2,"sectionIndex":1,"results":[${hitJson()}]}"""),
        ).isNull()
    }

    @Test
    fun `the excerpts are cut at their bounds`() {
        val pre = "a".repeat(250)
        val match = "b".repeat(500)
        val hit = """{"cfi":"$Cfi","pre":"$pre","match":"$match","post":"$pre"}"""

        val message = BridgeCodec.decode(resultsJson(groupJson(hit))) as PageMessage.SearchResults
        val result = message.groups.single().results.single()

        assertThat(result.pre).hasLength(BridgeLimits.MaxHitContext)
        assertThat(result.match).hasLength(BridgeLimits.MaxHitMatch)
        assertThat(result.post).hasLength(BridgeLimits.MaxHitContext)
    }

    @Test
    fun `opened carries section labels, which may be null, and tolerates their absence`() {
        val base = """"toc":[],"dir":"ltr","fixedLayout":false,"title":null"""

        val labelled = BridgeCodec.decode("""{"type":"opened",$base,"sectionLabels":[null,"One","Two"]}""")
        val old = BridgeCodec.decode("""{"type":"opened",$base}""")

        assertThat((labelled as PageMessage.Opened).sectionLabels).containsExactly(null, "One", "Two").inOrder()
        assertThat((old as PageMessage.Opened).sectionLabels).isEmpty()
        assertThat(BridgeCodec.decode("""{"type":"opened",$base,"sectionLabels":[1]}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"opened",$base,"sectionLabels":"x"}""")).isNull()
    }

    @Test
    fun `section labels are cut and kept to the sections that can exist`() {
        val long = "l".repeat(BridgeLimits.MaxLabelLength + 1)
        val base = """"toc":[],"dir":"ltr","fixedLayout":false,"title":null"""

        val message = BridgeCodec.decode("""{"type":"opened",$base,"sectionLabels":["$long"]}""")

        assertThat((message as PageMessage.Opened).sectionLabels.single()).hasLength(BridgeLimits.MaxLabelLength)
        val many = List(BridgeLimits.MaxSections + 3) { "null" }.joinToString(",")
        val capped = BridgeCodec.decode("""{"type":"opened",$base,"sectionLabels":[$many]}""")
        assertThat((capped as PageMessage.Opened).sectionLabels).hasSize(BridgeLimits.MaxSections)
    }

    private val relocated = """{"type":"relocated","cfi":"$Cfi","fraction":0.25,"sectionIndex":1,"sectionCount":12,
        "page":3,"pages":9,"tocId":4,"tocLabel":"C","atEnd":false,"moved":true"""

    @Test
    fun `relocated carries bookmark ids, empty when absent, cut at 64`() {
        val with = BridgeCodec.decode("$relocated,\"bookmarkIds\":[\"b1\",\"b2\"]}") as PageMessage.Relocated
        val old = BridgeCodec.decode("$relocated}") as PageMessage.Relocated
        val ids = (1..70).joinToString(",", "[", "]") { "\"b$it\"" }
        val many = BridgeCodec.decode("$relocated,\"bookmarkIds\":$ids}") as PageMessage.Relocated

        assertThat(with.bookmarkIds).containsExactly("b1", "b2").inOrder()
        assertThat(old.bookmarkIds).isEmpty()
        assertThat(many.bookmarkIds).hasSize(BridgeLimits.MaxBookmarkIds)
        assertThat(BridgeCodec.decode("$relocated,\"bookmarkIds\":[\"${"i".repeat(65)}\"]}")).isNull()
        assertThat(BridgeCodec.decode("$relocated,\"bookmarkIds\":[null]}")).isNull()
    }
}

class BridgeMarksEncodeTest {

    private val highlight = PageHighlight("h1", Cfi, "#FFEE00", PageHighlightStyle.Squiggly, note = true)
    private val highlightJson = """{"id":"h1","cfi":"$Cfi","color":"#FFEE00","style":"squiggly","note":true}"""

    @Test
    fun `highlights are encoded`() {
        assertThat(encoded(HostMessage.SetHighlights(listOf(highlight))))
            .isEqualTo(json("""{"type":"highlights","items":[$highlightJson]}"""))
        assertThat(encoded(HostMessage.SetHighlights(emptyList())))
            .isEqualTo(json("""{"type":"highlights","items":[]}"""))
        assertThat(encoded(HostMessage.PutHighlight(highlight)))
            .isEqualTo(json("""{"type":"putHighlight","item":$highlightJson}"""))
        assertThat(encoded(HostMessage.RemoveHighlight("h1")))
            .isEqualTo(json("""{"type":"removeHighlight","id":"h1"}"""))
    }

    @Test
    fun `the words of the styles and blends are the contract's`() {
        assertThat(PageHighlightStyle.entries.map { it.wire })
            .containsExactly("highlight", "underline", "strikethrough", "squiggly").inOrder()
        assertThat(PageBlend.entries.map { it.wire }).containsExactly("multiply", "normal").inOrder()
    }

    @Test
    fun `bookmarks are encoded`() {
        assertThat(encoded(HostMessage.SetBookmarks(listOf(PageBookmark("b1", Cfi)))))
            .isEqualTo(json("""{"type":"bookmarks","items":[{"id":"b1","cfi":"$Cfi"}]}"""))
    }

    @Test
    fun `the commands are encoded`() {
        assertThat(encoded(HostMessage.ClearSelection)).isEqualTo(json("""{"type":"clearSelection"}"""))
        assertThat(encoded(HostMessage.CancelSearch)).isEqualTo(json("""{"type":"cancelSearch"}"""))
        assertThat(encoded(HostMessage.ClearSearch)).isEqualTo(json("""{"type":"clearSearch"}"""))
        assertThat(encoded(HostMessage.CloseNote)).isEqualTo(json("""{"type":"closeNote"}"""))
        assertThat(encoded(HostMessage.Search(7, "maison")))
            .isEqualTo(json("""{"type":"search","searchId":7,"query":"maison"}"""))
        assertThat(encoded(HostMessage.ShowSearchResult(Cfi)))
            .isEqualTo(json("""{"type":"showSearchResult","cfi":"$Cfi"}"""))
    }
}
