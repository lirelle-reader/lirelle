package app.tfdn.lirelle.source.audiobookshelf.testing

/** The recorded answer [name] of the version [on], as it was written. */
internal fun fixtureBody(name: String, on: RecordedOn = RecordedOn.Latest): String {
    val path = "abs/${on.folder}/$name.json"
    val stream = RecordedOn::class.java.classLoader?.getResourceAsStream(path) ?: error("No fixture $path")
    return stream.bufferedReader().use { it.readText() }
}
