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
