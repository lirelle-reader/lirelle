package app.tfdn.lirelle.source.kavita.testing

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
