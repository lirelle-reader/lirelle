package app.tfdn.lirelle.source.audiobookshelf.testing

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** The recorded answers are read from both versions, and a name that was never recorded is said so. */
class FixturesTest {

    @Test
    fun `the libraries are read from each version`() {
        RecordedOn.entries.forEach { on ->
            val libraries = Json.parseToJsonElement(fixtureBody("libraries", on)).jsonObject["libraries"]!!.jsonArray
            val names = libraries.map { it.jsonObject["name"]!!.jsonPrimitive.content }

            assertWithMessage(on.name).that(names).containsExactly("Livres", "BD", "Podcasts").inOrder()
        }
    }

    @Test
    fun `the reader's answer keeps no token and no real e-mail`() {
        RecordedOn.entries.forEach { on ->
            val me = Json.parseToJsonElement(fixtureBody("me", on)) as JsonObject

            assertWithMessage(on.name).that(me["email"]!!.jsonPrimitive.content).isEqualTo("reader@example.org")
            assertWithMessage(on.name).that(me.keys).containsNoneOf("token", "accessToken", "refreshToken")
        }
    }

    @Test
    fun `a name never recorded fails`() {
        assertThrows(IllegalStateException::class.java) { fixtureBody("nothing-here") }
    }
}
