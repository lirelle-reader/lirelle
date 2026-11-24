package app.tfdn.lirelle.feature.settings.about

/** The id of Lirelle itself among the components: no artifact or bundled component has an id of this form. */
const val AppLicenceId = "app:lirelle"

private const val AppName = "Lirelle"
private const val GplId = "GPL-3.0-or-later"
private const val GplName = "GNU General Public License v3.0 or later"

/**
 * Lirelle itself, as a component: its name and its licence, the GPL version 3 or later.
 *
 * @param licenceText the licence's full text; null when it cannot be read, and the page then shows its name alone.
 */
fun appComponent(licenceText: String?): Component = Component(
    id = AppLicenceId,
    name = AppName,
    author = null,
    bundled = false,
    licences = listOf(Licence(shortName = GplId, name = GplName, text = licenceText)),
)
