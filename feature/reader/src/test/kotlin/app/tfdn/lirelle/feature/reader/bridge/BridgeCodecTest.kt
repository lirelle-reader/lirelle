package app.tfdn.lirelle.feature.reader.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

private const val Cfi = "epubcfi(/6/4!/4/2/1:0)"

private const val RelocatedJson = """{"type":"relocated","cfi":"$Cfi","fraction":0.25,"sectionIndex":1,
    "sectionCount":12,"page":3,"pages":9,"tocId":4,"tocLabel":"Chapter 2","atEnd":false,"moved":true}"""

private val Relocated = PageMessage.Relocated(
    cfi = Cfi, fraction = 0.25, sectionIndex = 1, sectionCount = 12, page = 3, pages = 9,
    tocId = 4, tocLabel = "Chapter 2", atEnd = false, moved = true,
)

private val Settings = PageSettings(
    background = "#101010", foreground = "#eeeeee", link = "#88aaff", dark = true,
    font = PageFont.Dyslexic, fontSizePx = 20, lineHeight = 1.6, marginPercent = 7.0,
    align = PageAlign.Justify, hyphenate = false, flow = PageFlow.Scrolled, maxColumns = 2,
    brightnessEdge = PageBrightnessEdge.Off,
    highlightBlend = PageBlend.Normal, highlightOpacity = 0.5, searchColor = "#112233", footnotePanel = false,
    noteLabels = PageNoteLabels(goTo = "Aller à la note", title = "Note"),
)

private const val SettingsJson = """{"background":"#101010","foreground":"#eeeeee","link":"#88aaff","dark":true,
    "font":"dyslexic","fontSizePx":20,"lineHeight":1.6,"marginPercent":7.0,"align":"justify","hyphenate":false,
    "flow":"scrolled","maxColumns":2,"brightnessEdge":"off","highlightBlend":"normal","highlightOpacity":0.5,
    "searchColor":"#112233","footnotePanel":false,"noteLabels":{"goTo":"Aller à la note","title":"Note"}}"""

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

private fun encoded(message: HostMessage): JsonElement = json(BridgeCodec.encode(message))

class BridgeCodecDecodeTest {

    @Test
    fun `ready is decoded`() {
        assertThat(BridgeCodec.decode("""{"type":"ready"}""")).isEqualTo(PageMessage.Ready)
    }

    @Test
    fun `pastEnd is decoded, whatever else it carries`() {
        assertThat(BridgeCodec.decode("""{"type":"pastEnd"}""")).isEqualTo(PageMessage.PastEnd)
        assertThat(BridgeCodec.decode("""{"type":"pastEnd","later":1}""")).isEqualTo(PageMessage.PastEnd)
        assertThat(BridgeCodec.decode("""{"type":"pastend"}""")).isNull()
    }

    @Test
    fun `turning is decoded, whatever else it carries`() {
        assertThat(BridgeCodec.decode("""{"type":"turning"}""")).isEqualTo(PageMessage.Turning)
        assertThat(BridgeCodec.decode("""{"type":"turning","later":1}""")).isEqualTo(PageMessage.Turning)
        assertThat(BridgeCodec.decode("""{"type":"Turning"}""")).isNull()
    }

    @Test
    fun `opened is decoded with its table of contents`() {
        val message = BridgeCodec.decode(
            """{"type":"opened","toc":[{"id":0,"label":"One","href":"ch1.xhtml","depth":0},
                {"id":1,"label":"","href":"ch1.xhtml#a","depth":1}],"dir":"rtl","fixedLayout":true,"title":"A book"}""",
        )

        assertThat(message).isEqualTo(
            PageMessage.Opened(
                toc = listOf(TocEntry(0, "One", "ch1.xhtml", 0), TocEntry(1, "", "ch1.xhtml#a", 1)),
                dir = PageDirection.Rtl,
                fixedLayout = true,
                title = "A book",
            ),
        )
    }

    @Test
    fun `opened without a title or a table of contents is decoded`() {
        val message = BridgeCodec.decode("""{"type":"opened","toc":[],"dir":"ltr","fixedLayout":false,"title":null}""")

        assertThat(message).isEqualTo(PageMessage.Opened(emptyList(), PageDirection.Ltr, false, null))
    }

    @Test
    fun `relocated is decoded`() {
        assertThat(BridgeCodec.decode(RelocatedJson)).isEqualTo(Relocated)
    }

    @Test
    fun `a relocation the host asked for says so, an old one without the flag does not`() {
        assertThat(Relocated.byHost).isFalse()
        val asked = BridgeCodec.decode(RelocatedJson.replace("\"moved\":true", "\"moved\":true,\"byHost\":true"))
        assertThat(asked).isEqualTo(Relocated.copy(byHost = true))
        val notAsked = BridgeCodec.decode(RelocatedJson.replace("\"moved\":true", "\"moved\":true,\"byHost\":false"))
        assertThat(notAsked).isEqualTo(Relocated)
        val nullFlag = BridgeCodec.decode(RelocatedJson.replace("\"moved\":true", "\"moved\":true,\"byHost\":null"))
        assertThat(nullFlag).isEqualTo(Relocated)
    }

    @Test
    fun `a byHost flag that is not a boolean fails the relocation`() {
        listOf("\"true\"", "1", "{}").forEach { value ->
            val json = RelocatedJson.replace("\"moved\":true", "\"moved\":true,\"byHost\":$value")
            assertThat(BridgeCodec.decode(json)).isNull()
        }
    }

    @Test
    fun `relocated without pages or a chapter is decoded`() {
        val message = BridgeCodec.decode(
            """{"type":"relocated","cfi":"$Cfi","fraction":1,"sectionIndex":0,"sectionCount":1,"page":null,
                "pages":null,"tocId":null,"tocLabel":null,"atEnd":true,"moved":false}""",
        )

        assertThat(message).isEqualTo(
            Relocated.copy(
                fraction = 1.0, sectionIndex = 0, sectionCount = 1, page = null, pages = null,
                tocId = null, tocLabel = null, atEnd = true, moved = false,
            ),
        )
    }

    @Test
    fun `tap, brightness, link and error are decoded`() {
        assertThat(BridgeCodec.decode("""{"type":"tap","x":0.5,"y":1}""")).isEqualTo(PageMessage.Tap(0.5, 1.0))
        assertThat(BridgeCodec.decode("""{"type":"brightness","phase":"move","delta":-0.02}"""))
            .isEqualTo(PageMessage.Brightness(BrightnessPhase.Move, -0.02))
        assertThat(BridgeCodec.decode("""{"type":"link","href":"ch2.xhtml#n1","external":false}"""))
            .isEqualTo(PageMessage.Link("ch2.xhtml#n1", external = false))
        assertThat(BridgeCodec.decode("""{"type":"error","code":"unsupported_engine","message":"Missing groupBy"}"""))
            .isEqualTo(PageMessage.Error(PageErrorCode.UnsupportedEngine, "Missing groupBy"))
        assertThat(BridgeCodec.decode("""{"type":"error","code":"cannot_open","message":"protected"}"""))
            .isEqualTo(PageMessage.Error(PageErrorCode.CannotOpen, "protected"))
        assertThat(BridgeCodec.decode("""{"type":"error","code":"open_failed","message":""}"""))
            .isEqualTo(PageMessage.Error(PageErrorCode.OpenFailed, ""))
        assertThat(BridgeCodec.decode("""{"type":"error","code":"section_failed","message":"x"}"""))
            .isEqualTo(PageMessage.Error(PageErrorCode.SectionFailed, "x"))
    }

    @Test
    fun `an external link of any scheme is carried as data`() {
        val message = BridgeCodec.decode("""{"type":"link","href":"javascript:alert(\"x\")","external":true}""")

        assertThat(message).isEqualTo(PageMessage.Link("javascript:alert(\"x\")", external = true))
    }

    @Test
    fun `unknown fields are ignored`() {
        val message = BridgeCodec.decode("""{"type":"tap","x":0.5,"y":0.5,"pressure":3,"extra":{"a":[1,2]}}""")

        assertThat(message).isEqualTo(PageMessage.Tap(0.5, 0.5))
    }
}

class BridgeCodecRefusalTest {

    private fun relocatedWith(field: String, value: String) =
        BridgeCodec.decode(RelocatedJson.replace(Regex(""""$field":[^,}]+"""), """"$field":$value"""))

    @Test
    fun `the relocated sample is accepted before it is damaged`() {
        assertThat(relocatedWith("page", "3")).isEqualTo(Relocated)
    }

    @Test
    fun `what is not a JSON object with a known type is null`() {
        val inputs = listOf(
            "", "not json", "{", "[]", "null", "42", "\"ready\"", "{}", """{"type":null}""", """{"type":7}""",
            """{"type":"launch"}""", """{"type":"open"}""", """{"type":"Ready"}""", """{"type":["ready"]}""",
            "[".repeat(100_000),
        )

        inputs.forEach { assertThat(BridgeCodec.decode(it)).isNull() }
    }

    @Test
    fun `a missing required field is null`() {
        listOf("cfi", "fraction", "sectionIndex", "sectionCount", "atEnd", "moved").forEach { field ->
            val damaged = RelocatedJson.replace(Regex(""""$field":[^,}]+,?"""), "").replace(Regex(""",\s*\}"""), "}")

            assertThat(BridgeCodec.decode(damaged)).isNull()
        }
        assertThat(BridgeCodec.decode("""{"type":"tap","x":0.5}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"link","href":"a"}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"opened","dir":"ltr","fixedLayout":false,"title":null}""")).isNull()
    }

    @Test
    fun `a field of the wrong kind is null`() {
        assertThat(relocatedWith("fraction", "\"0.25\"")).isNull()
        assertThat(relocatedWith("sectionIndex", "1.5")).isNull()
        assertThat(relocatedWith("atEnd", "\"false\"")).isNull()
        assertThat(relocatedWith("atEnd", "0")).isNull()
        assertThat(relocatedWith("tocLabel", "12")).isNull()
        assertThat(relocatedWith("page", "\"3\"")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"link","href":["a"],"external":true}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"opened","toc":{},"dir":"ltr","fixedLayout":false,"title":null}"""))
            .isNull()
        assertThat(BridgeCodec.decode("""{"type":"opened","toc":["a"],"dir":"ltr","fixedLayout":false,"title":null}"""))
            .isNull()
    }

    @Test
    fun `a number out of range or not finite is null`() {
        listOf("-0.01", "1.01", "1e400", "-1e400", "NaN", "Infinity").forEach {
            assertThat(relocatedWith("fraction", it)).isNull()
        }
        assertThat(relocatedWith("sectionIndex", "-1")).isNull()
        assertThat(relocatedWith("sectionIndex", "12")).isNull()
        assertThat(relocatedWith("sectionCount", "0")).isNull()
        assertThat(relocatedWith("sectionCount", "${BridgeLimits.MaxSections + 1}")).isNull()
        assertThat(relocatedWith("page", "-1")).isNull()
        assertThat(relocatedWith("pages", "${BridgeLimits.MaxPages + 1}")).isNull()
        assertThat(relocatedWith("tocId", "-1")).isNull()
        assertThat(relocatedWith("tocId", "4294967296")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"tap","x":1.2,"y":0.5}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"tap","x":0.5,"y":-0.1}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"brightness","phase":"move","delta":1.5}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"brightness","phase":"move","delta":-1e999}""")).isNull()
    }

    @Test
    fun `an unknown word where a fixed set is expected is null`() {
        assertThat(BridgeCodec.decode("""{"type":"brightness","phase":"pause","delta":0}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"error","code":"exploded","message":"x"}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"opened","toc":[],"dir":"up","fixedLayout":false,"title":null}"""))
            .isNull()
    }

    @Test
    fun `a position that does not look like a CFI is null`() {
        listOf("\"\"", "\"/6/4!/4\"", "\"epubcfi(\"", "\"epubcfi()\"", "\"javascript:epubcfi(/6)\"", "\"epubcfi(/6/4\"")
            .forEach { assertThat(relocatedWith("cfi", it)).isNull() }
        val long = "epubcfi(/" + "6".repeat(BridgeLimits.MaxCfiLength) + ")"
        assertThat(relocatedWith("cfi", "\"$long\"")).isNull()
    }

    @Test
    fun `an oversized string is null`() {
        val href = "a".repeat(BridgeLimits.MaxHrefLength + 1)
        val text = "a".repeat(BridgeLimits.MaxErrorMessageLength + 1)

        assertThat(BridgeCodec.decode("""{"type":"link","href":"$href","external":true}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"error","code":"open_failed","message":"$text"}""")).isNull()
        assertThat(BridgeCodec.decode("""{"type":"ready","padding":"${"a".repeat(BridgeLimits.MaxMessageLength)}"}"""))
            .isNull()
    }

    @Test
    fun `strings at their limit are accepted`() {
        val label = "a".repeat(BridgeLimits.MaxLabelLength)

        assertThat(relocatedWith("tocLabel", "\"$label\"")).isEqualTo(Relocated.copy(tocLabel = label))
    }

    @Test
    fun `a table of contents that is too deep is null`() {
        fun opened(depth: Int) = BridgeCodec.decode(
            """{"type":"opened","toc":[{"id":0,"label":"c","href":"h","depth":$depth}],"dir":"ltr",
                "fixedLayout":false}""",
        )

        assertThat(opened(BridgeLimits.MaxTocDepth)).isNotNull()
        assertThat(opened(BridgeLimits.MaxTocDepth + 1)).isNull()
        assertThat(opened(-1)).isNull()
    }
}

/** A book with an over-long title or contents must still open: those are cut, not refused. */
class BridgeCodecTruncationTest {

    private val limit = BridgeLimits.MaxLabelLength

    private fun opened(toc: String, title: String = "null") = BridgeCodec.decode(
        """{"type":"opened","toc":[$toc],"dir":"ltr","fixedLayout":false,"title":$title}""",
    ) as PageMessage.Opened?

    private fun entry(id: Int, label: String = "c", href: String = "h") =
        """{"id":$id,"label":"$label","href":"$href","depth":0}"""

    @Test
    fun `an over-long title is cut to the limit`() {
        val message = opened(toc = "", title = "\"${"a".repeat(limit)}bcd\"")

        assertThat(message?.title).isEqualTo("a".repeat(limit))
    }

    @Test
    fun `an over-long contents label is cut to the limit, and the others are left as they are`() {
        val message = opened(entry(0, label = "One") + "," + entry(1, label = "a".repeat(limit) + "bcd"))

        assertThat(message?.toc)
            .containsExactly(TocEntry(0, "One", "h", 0), TocEntry(1, "a".repeat(limit), "h", 0))
            .inOrder()
    }

    @Test
    fun `a cut never splits a character made of two`() {
        // U+1F4D6, an open book: two UTF-16 units, the first of which would be the last one kept.
        val book = "\uD83D\uDCD6"
        val long = "a".repeat(limit - 1) + book + "b"
        val whole = "a".repeat(limit - 2) + book + "b"

        assertThat(opened(toc = "", title = "\"$long\"")?.title).isEqualTo("a".repeat(limit - 1))
        assertThat(opened(entry(0, label = long))?.toc?.single()?.label).isEqualTo("a".repeat(limit - 1))
        assertThat(opened(toc = "", title = "\"$whole\"")?.title).isEqualTo("a".repeat(limit - 2) + book)
    }

    @Test
    fun `a table of contents that is too long keeps its first entries`() {
        val count = BridgeLimits.MaxTocEntries + 5

        val message = opened((0 until count).joinToString(",") { entry(it) })

        assertThat(message?.toc).hasSize(BridgeLimits.MaxTocEntries)
        assertThat(message?.toc?.last()?.id).isEqualTo(BridgeLimits.MaxTocEntries - 1)
    }

    @Test
    fun `an entry with an over-long address is left out, not the whole table`() {
        val href = "a".repeat(BridgeLimits.MaxHrefLength + 1)

        val message = opened(entry(0) + "," + entry(1, href = href) + "," + entry(2))

        assertThat(message?.toc?.map { it.id }).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `what is of the wrong kind still refuses the whole message`() {
        assertThat(opened(toc = "", title = "12")).isNull()
        assertThat(opened(toc = "", title = "[\"a\"]")).isNull()
        assertThat(opened("""{"id":0,"label":7,"href":"h","depth":0}""")).isNull()
        assertThat(opened("""{"id":0,"label":"c","href":7,"depth":0}""")).isNull()
        assertThat(opened("""{"id":0,"label":"c","depth":0}""")).isNull()
        assertThat(opened(entry(0) + ",\"a\"")).isNull()
        // An entry left out for its address is still checked for the rest.
        val href = "a".repeat(BridgeLimits.MaxHrefLength + 1)
        assertThat(opened("""{"id":-1,"label":"c","href":"$href","depth":0}""")).isNull()
    }

    @Test
    fun `an over-long chapter label of a position is cut to the limit`() {
        val label = "a".repeat(limit - 1) + "\uD83D\uDCD6"

        val message = BridgeCodec.decode(RelocatedJson.replace("\"Chapter 2\"", "\"$label\""))

        assertThat(message).isEqualTo(Relocated.copy(tocLabel = "a".repeat(limit - 1)))
    }
}

class BridgeCodecEncodeTest {

    @Test
    fun `open carries the base, the position and the settings`() {
        val message = HostMessage.Open(base = "/book/3f2a/", cfi = Cfi, fraction = 0.5, settings = Settings)

        assertThat(encoded(message)).isEqualTo(
            json(
                """{"type":"open","base":"/book/3f2a/","cfi":"$Cfi","fraction":0.5,"format":"epub",
                    "settings":$SettingsJson}""",
            ),
        )
    }

    @Test
    fun `open says how the book is given, as an EPUB, a Kindle book or a FictionBook`() {
        val formats = mapOf(BookFormat.Epub to "epub", BookFormat.Mobi to "mobi", BookFormat.Fb2 to "fb2")
        formats.forEach { (format, wire) ->
            val message = HostMessage.Open("/book/3f2a/", cfi = null, fraction = null, Settings, format)

            assertThat(encoded(message).jsonObject.getValue("format")).isEqualTo(JsonPrimitive(wire))
        }
    }

    @Test
    fun `open without a position says null for both`() {
        val message = HostMessage.Open(base = "/book/3f2a/", cfi = null, fraction = null, settings = Settings)

        assertThat(encoded(message)).isEqualTo(
            json(
                """{"type":"open","base":"/book/3f2a/","cfi":null,"fraction":null,"format":"epub",
                    "settings":$SettingsJson}""",
            ),
        )
    }

    @Test
    fun `goTo carries exactly one target`() {
        assertThat(encoded(HostMessage.GoTo.Cfi(Cfi))).isEqualTo(json("""{"type":"goTo","cfi":"$Cfi"}"""))
        assertThat(encoded(HostMessage.GoTo.Href("ch2.xhtml#a")))
            .isEqualTo(json("""{"type":"goTo","href":"ch2.xhtml#a"}"""))
        assertThat(encoded(HostMessage.GoTo.Fraction(0.75))).isEqualTo(json("""{"type":"goTo","fraction":0.75}"""))
    }

    @Test
    fun `next, prev and settings are encoded`() {
        assertThat(encoded(HostMessage.Next)).isEqualTo(json("""{"type":"next"}"""))
        assertThat(encoded(HostMessage.Prev)).isEqualTo(json("""{"type":"prev"}"""))
        assertThat(encoded(HostMessage.ApplySettings(Settings)))
            .isEqualTo(json("""{"type":"settings","settings":$SettingsJson}"""))
    }

    @Test
    fun `every word of the settings is the contract's`() {
        fun settings(change: PageSettings.() -> PageSettings) =
            BridgeCodec.encode(HostMessage.ApplySettings(Settings.change()))

        assertThat(PageFont.entries.map { it.wire }).containsExactly("book", "serif", "sans", "dyslexic").inOrder()
        assertThat(PageAlign.entries.map { it.wire }).containsExactly("book", "justify", "start").inOrder()
        assertThat(PageFlow.entries.map { it.wire }).containsExactly("paginated", "scrolled").inOrder()
        assertThat(PageBrightnessEdge.entries.map { it.wire }).containsExactly("left", "right", "off").inOrder()
        assertThat(settings { copy(font = PageFont.Book) }).contains(""""font":"book"""")
        assertThat(settings { copy(brightnessEdge = PageBrightnessEdge.Left) }).contains(""""brightnessEdge":"left"""")
    }

    @Test
    fun `a number that is not finite is sent as null, never as a word JSON does not know`() {
        val text = BridgeCodec.encode(HostMessage.GoTo.Fraction(Double.NaN))

        assertThat(json(text)).isEqualTo(json("""{"type":"goTo","fraction":null}"""))
    }
}

class BridgeCodecScriptTest {

    private val nasty = "a\"b\\c</script><!--'  \n\r\u0000`\${x}"

    @Test
    fun `the call hands the message over as one string literal`() {
        val message = HostMessage.GoTo.Href(nasty)

        val script = BridgeCodec.receiveCall(message)

        assertThat(script).startsWith("window.lirelle.receive(\"")
        assertThat(script).endsWith("\")")
        val literal = script.removePrefix("window.lirelle.receive(").removeSuffix(")")
        assertThat(Json.decodeFromString<String>(literal)).isEqualTo(BridgeCodec.encode(message))
        assertThat(encoded(message)).isEqualTo(json("""{"type":"goTo","href":${Json.encodeToString(nasty)}}"""))
    }

    @Test
    fun `nothing in a message can end the literal or the script`() {
        val script = BridgeCodec.receiveCall(HostMessage.GoTo.Href(nasty))
        val inside = script.removePrefix("window.lirelle.receive(\"").removeSuffix("\")")

        assertThat(inside).doesNotContain(" ")
        assertThat(inside).doesNotContain(" ")
        assertThat(inside).doesNotContain("<")
        assertThat(inside).doesNotContain("\n")
        assertThat(inside).doesNotContain("\r")
        assertThat(inside).doesNotContain("\u0000")
        // Every quote inside the literal is escaped: an even number of backslashes never precedes one.
        assertThat(Regex("""(?<!\\)(\\\\)*"""").containsMatchIn(inside)).isFalse()
    }
}
