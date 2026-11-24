package app.tfdn.lirelle.feature.reader.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test

private const val Cfi = "epubcfi(/6/4!/4/2/1:0)"

private const val RelocatedJson = """{"type":"relocated","cfi":"$Cfi","fraction":0.25,"sectionIndex":1,
    "sectionCount":12,"page":3,"pages":9,"tocId":4,"tocLabel":"Chapter 2","atEnd":false,"moved":true,
    "byHost":false"""

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

private fun encoded(message: HostMessage): JsonElement = json(BridgeCodec.encode(message))

class BridgeNarrationDecodeTest {

    @Test
    fun `a relocation made to follow the narration says so, and one from before the flag does not`() {
        val followed = BridgeCodec.decode("""$RelocatedJson,"byNarration":true}""") as PageMessage.Relocated
        assertThat(followed.byNarration).isTrue()
        val user = BridgeCodec.decode("""$RelocatedJson,"byNarration":false}""") as PageMessage.Relocated
        assertThat(user.byNarration).isFalse()
        val old = BridgeCodec.decode("$RelocatedJson}") as PageMessage.Relocated
        assertThat(old.byNarration).isFalse()
        assertThat(BridgeCodec.decode("""$RelocatedJson,"byNarration":"yes"}""")).isNull()
    }

    @Test
    fun `a relocation says whether the user made it, and one from before the flag is taken as theirs`() {
        val resized = BridgeCodec.decode("""$RelocatedJson,"userMove":false}""") as PageMessage.Relocated
        assertThat(resized.userMove).isFalse()
        val turned = BridgeCodec.decode("""$RelocatedJson,"userMove":true}""") as PageMessage.Relocated
        assertThat(turned.userMove).isTrue()
        val old = BridgeCodec.decode("$RelocatedJson}") as PageMessage.Relocated
        assertThat(old.userMove).isTrue()
        assertThat(BridgeCodec.decode("""$RelocatedJson,"userMove":"no"}""")).isNull()
    }

    @Test
    fun `the sentence found is decoded, and none found is null`() {
        assertThat(BridgeCodec.decode("""{"type":"narrationFound","requestId":7,"sectionIndex":2,"id":"s2-s14"}"""))
            .isEqualTo(PageMessage.NarrationFound(requestId = 7, sectionIndex = 2, id = "s2-s14"))
        assertThat(BridgeCodec.decode("""{"type":"narrationFound","requestId":7,"sectionIndex":2,"id":null}"""))
            .isEqualTo(PageMessage.NarrationFound(requestId = 7, sectionIndex = 2, id = null))
    }

    @Test
    fun `the position of a sentence is decoded, and an unknown sentence has none`() {
        assertThat(BridgeCodec.decode("""{"type":"narrationCfiOf","requestId":3,"cfi":"$Cfi"}"""))
            .isEqualTo(PageMessage.NarrationCfiOf(requestId = 3, cfi = Cfi))
        assertThat(BridgeCodec.decode("""{"type":"narrationCfiOf","requestId":3,"cfi":null}"""))
            .isEqualTo(PageMessage.NarrationCfiOf(requestId = 3, cfi = null))
    }

    @Test
    fun `the position of a sentence says how far into the book it is, when the page knows`() {
        assertThat(BridgeCodec.decode("""{"type":"narrationCfiOf","requestId":3,"cfi":"$Cfi","fraction":0.42}"""))
            .isEqualTo(PageMessage.NarrationCfiOf(requestId = 3, cfi = Cfi, fraction = 0.42))
        assertThat(BridgeCodec.decode("""{"type":"narrationCfiOf","requestId":3,"cfi":"$Cfi","fraction":null}"""))
            .isEqualTo(PageMessage.NarrationCfiOf(requestId = 3, cfi = Cfi, fraction = null))
    }

    @Test
    fun `visibility and a missing sentence are decoded`() {
        assertThat(BridgeCodec.decode("""{"type":"narrationVisible","visible":false}"""))
            .isEqualTo(PageMessage.NarrationVisible(visible = false))
        assertThat(BridgeCodec.decode("""{"type":"narrationMissing","sectionIndex":4,"id":"s4-s1"}"""))
            .isEqualTo(PageMessage.NarrationMissing(sectionIndex = 4, id = "s4-s1"))
    }

    @Test
    fun `malformed narration messages are refused`() {
        val longId = "x".repeat(BridgeLimits.MaxElementIdLength + 1)
        listOf(
            """{"type":"narrationFound","requestId":-1,"sectionIndex":2,"id":"a"}""",
            """{"type":"narrationFound","requestId":1.5,"sectionIndex":2,"id":"a"}""",
            """{"type":"narrationFound","requestId":1,"sectionIndex":-2,"id":"a"}""",
            """{"type":"narrationFound","requestId":1,"sectionIndex":2,"id":"$longId"}""",
            """{"type":"narrationFound","requestId":1,"sectionIndex":2,"id":3}""",
            """{"type":"narrationCfiOf","requestId":1,"cfi":"not a cfi"}""",
            """{"type":"narrationCfiOf","cfi":"$Cfi"}""",
            """{"type":"narrationCfiOf","requestId":1,"cfi":"$Cfi","fraction":1.5}""",
            """{"type":"narrationCfiOf","requestId":1,"cfi":"$Cfi","fraction":"0.5"}""",
            """{"type":"narrationVisible","visible":"true"}""",
            """{"type":"narrationVisible"}""",
            """{"type":"narrationMissing","sectionIndex":1}""",
            """{"type":"narrationMissing","sectionIndex":1,"id":"$longId"}""",
        ).forEach { assertThat(BridgeCodec.decode(it)).isNull() }
    }

    @Test
    fun `an id at the limit is kept whole`() {
        val id = "x".repeat(BridgeLimits.MaxElementIdLength)

        assertThat(BridgeCodec.decode("""{"type":"narrationMissing","sectionIndex":0,"id":"$id"}"""))
            .isEqualTo(PageMessage.NarrationMissing(sectionIndex = 0, id = id))
    }
}

class BridgeNarrationEncodeTest {

    @Test
    fun `the sentence heard is sent with its section, its state and whether to follow it`() {
        assertThat(encoded(HostMessage.NarrationMark(sectionIndex = 3, id = "s3-s9", paused = true, follow = false)))
            .isEqualTo(
                json("""{"type":"narrationMark","sectionIndex":3,"id":"s3-s9","paused":true,"follow":false}"""),
            )
        assertThat(encoded(HostMessage.NarrationClear)).isEqualTo(json("""{"type":"narrationClear"}"""))
    }

    @Test
    fun `a search for the sentence to start from carries the narrated ids and what to start from`() {
        val message = HostMessage.NarrationFind(
            requestId = 5, sectionIndex = 1, ids = listOf("s1-s1", "s1-s2"), what = NarrationTarget.Selection,
        )

        assertThat(encoded(message)).isEqualTo(
            json(
                """{"type":"narrationFind","requestId":5,"sectionIndex":1,"ids":["s1-s1","s1-s2"],
                    "what":"selection"}""",
            ),
        )
        assertThat(NarrationTarget.entries.map { it.wire }).containsExactly("page", "selection").inOrder()
    }

    @Test
    fun `the narrated ids sent are the first 5000`() {
        val ids = (1..BridgeLimits.MaxNarrationIds + 10).map { "s1-s$it" }

        val sent = encoded(HostMessage.NarrationFind(1, 0, ids, NarrationTarget.Page)) as JsonObject

        assertThat((sent["ids"] as JsonArray).size).isEqualTo(BridgeLimits.MaxNarrationIds)
        assertThat(BridgeLimits.MaxNarrationIds).isEqualTo(5_000)
    }

    @Test
    fun `the position of a sentence is asked for by its section and id`() {
        assertThat(encoded(HostMessage.NarrationCfi(requestId = 9, sectionIndex = 2, id = "s2-s4")))
            .isEqualTo(json("""{"type":"narrationCfi","requestId":9,"sectionIndex":2,"id":"s2-s4"}"""))
    }
}
