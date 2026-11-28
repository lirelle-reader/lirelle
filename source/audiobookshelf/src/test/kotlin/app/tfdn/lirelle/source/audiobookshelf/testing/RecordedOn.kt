package app.tfdn.lirelle.source.audiobookshelf.testing

/**
 * The Audiobookshelf version an answer was recorded on, as lecteur, on the test server (2026-11-28):
 * `src/test/resources/abs/<folder>/<name>.json`, read with [fixtureBody]. The whole Books list is trimmed to 40
 * items and the authors to 10; the e-mail is replaced and every token taken out.
 */
internal enum class RecordedOn(val folder: String) {
    /** 2.38.0, the latest release. */
    Latest("v2380"),

    /** 2.26.3, the oldest version the app works with. */
    Minimum("v2263"),
}
