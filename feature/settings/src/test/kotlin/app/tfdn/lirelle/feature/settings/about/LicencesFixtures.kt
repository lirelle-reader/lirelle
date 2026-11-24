package app.tfdn.lirelle.feature.settings.about

/**
 * A small list in the format of the file generated at build time: three libraries and two bundled
 * components; one library has two licences, one names nobody, one licence has no text. One library is
 * there twice, the second time as its artifact for one platform: same name, author and licence.
 */
fun licencesFixture(): String =
    checkNotNull(Licences::class.java.getResource("/licences-fixture.json")) { "The fixture is missing." }.readText()
