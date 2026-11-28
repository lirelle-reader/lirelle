package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.source.audiobookshelf.library.AbsHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer

/** The recorded server's library of comics on 2.26.3. */
internal const val BdIdMinimum = "04c36841-fc5c-4c09-9920-fa33496a3d11"

/** The recorded server's library of comics, as recorded on [on]. */
internal fun bdId(on: RecordedOn): String = if (on == RecordedOn.Latest) BdId else BdIdMinimum

/** The path of a library's Home shelves. */
internal fun personalizedPath(libraryId: String): String = "/api/libraries/$libraryId/personalized"

/** The path of the items the account has started. */
internal const val InProgressPath = "/api/me/items-in-progress"

/**
 * Home's rows of the profile's server on [server], made with what [browse] holds (whose clock the test moves for the
 * libraries and the progress), and the time Home's shelves are shared by, in milliseconds, which the test moves.
 */
internal class HomeRig(server: MockWebServer) {
    val browse = BrowseRig(server)
    var now = 0L
    val home = AbsHome(browse.profileServer, browse.progress, browse.whole) { now }
}

/**
 * [routes] answer the libraries, the reader's progress, the items in progress and the Home shelves of Livres and BD
 * as recorded on [on].
 */
internal fun answerRecordedHome(routes: LibraryRoutes, on: RecordedOn = RecordedOn.Latest) {
    answerRecordedAccount(routes, on)
    routes.answer(InProgressPath, fixtureBody("items-in-progress", on))
    routes.answer(personalizedPath(livresId(on)), fixtureBody("personalized-livres", on))
    routes.answer(personalizedPath(bdId(on)), fixtureBody("personalized-bd", on))
}

/** The recorded Home shelves [name], as recorded on [on]. */
internal fun recordedShelves(name: String, on: RecordedOn = RecordedOn.Latest): List<JsonObject> =
    Json.parseToJsonElement(fixtureBody(name, on)).jsonArray.map { it.jsonObject }

/** The entities of the recorded shelf [shelfId] of the Home shelves [name]. */
internal fun recordedShelfEntities(name: String, shelfId: String): List<JsonObject> =
    recordedShelves(name).first { it.id == shelfId }.getValue("entities").jsonArray.map { it.jsonObject }

/** A Home shelf [id] of books holding [entities], of [total] books on the server. */
internal fun bookShelf(id: String, entities: List<JsonElement>, total: Int = entities.size): JsonObject = JsonObject(
    mapOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive("book"),
        "entities" to JsonArray(entities),
        "total" to JsonPrimitive(total),
    ),
)

/** These Home shelves with [shelf] put in place of the one of its id, or added. */
internal fun List<JsonObject>.withShelf(shelf: JsonObject): String =
    JsonArray(filter { it.id != shelf.id } + shelf).toString()

/**
 * An item as the `continue-series` shelf gives it: the server names the series it continues in its metadata, as one
 * object, with the item's place in it.
 */
internal fun JsonObject.continuing(seriesId: String, name: String, sequence: String): JsonObject {
    val media = getValue("media").jsonObject
    val metadata = media.getValue("metadata").jsonObject
    val series = JsonObject(
        mapOf("id" to JsonPrimitive(seriesId), "name" to JsonPrimitive(name), "sequence" to JsonPrimitive(sequence)),
    )
    return with("media", media.with("metadata", metadata.with("series", series)))
}

/** The title of a recorded item. */
internal val JsonObject.title: String
    get() = (getValue("media").jsonObject.getValue("metadata").jsonObject.getValue("title") as JsonPrimitive).content

private val JsonObject.id: String get() = (getValue("id") as JsonPrimitive).content
