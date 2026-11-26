package app.tfdn.lirelle.source.kavita.testing

/**
 * The answers recorded on the Kavita test server (2026-11-26), trimmed to a few items per list and with the
 * server's paths replaced: `src/test/resources/kavita/<version>/<name>.json`. A list that came with Kavita's
 * `Pagination` header is kept as `{"pagination": "...", "body": [...]}`: [fixtureBody] gives the body alone.
 */
enum class RecordedOn(val folder: String) {
    Latest("v0914"),
    Minimum("v0891"),
}

/** The recorded answer [name] of [version], as it was written. */
fun fixture(name: String, version: RecordedOn = RecordedOn.Latest): String {
    val path = "/kavita/${version.folder}/$name.json"
    val stream = RecordedOn::class.java.getResourceAsStream(path) ?: error("No fixture $path")
    return stream.bufferedReader().use { it.readText() }
}

/** The body of the recorded answer [name]: without the pagination it may have been kept with. */
fun fixtureBody(name: String, version: RecordedOn = RecordedOn.Latest): String {
    val text = fixture(name, version)
    val element = kotlinx.serialization.json.Json.parseToJsonElement(text)
    val body = (element as? kotlinx.serialization.json.JsonObject)
        ?.takeIf { it.keys == setOf("pagination", "body") }
        ?.get("body")
    return body?.toString() ?: text
}

/** The `Pagination` header that came with the recorded answer [name], null when none did. */
fun fixturePagination(name: String, version: RecordedOn = RecordedOn.Latest): String? {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(fixture(name, version))
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    if (obj.keys != setOf("pagination", "body")) return null
    return (obj["pagination"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
