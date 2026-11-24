package app.tfdn.lirelle.feature.settings.about

/**
 * A component the app ships, and the licences it comes under.
 *
 * @property author who made it: its first developer, else its organisation; null when the list names nobody.
 * @property bundled it ships outside Gradle: the reading engine, the fonts.
 */
data class Component(
    val id: String,
    val name: String,
    val author: String?,
    val bundled: Boolean,
    val licences: List<Licence>,
)

/**
 * A licence a component comes under.
 *
 * @property shortName what the chip shows: the SPDX id, else the licence's name.
 * @property text the licence's full text; null when the list has none.
 */
data class Licence(val shortName: String, val name: String, val text: String?)

/**
 * The components, the bundled ones apart; each list sorted by name, case ignored.
 *
 * @property app Lirelle itself, shown from its own row of About rather than in the lists; its licence's text is the
 *   one the source read.
 */
data class Licences(
    val bundled: List<Component>,
    val libraries: List<Component>,
    val app: Component = appComponent(licenceText = null),
) {

    /** The component of that id: the app, or one of either list. */
    fun component(id: String): Component? =
        if (id == app.id) app else bundled.firstOrNull { it.id == id } ?: libraries.firstOrNull { it.id == id }
}
