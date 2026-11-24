package app.tfdn.lirelle.feature.settings.about

import android.util.Log
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License

/** The tag of the components declared by hand: what ships outside Gradle. */
private const val BundledTag = "bundled"

/** The tag of the warning logged when the list cannot be read. */
private const val LogTag = "Licences"

/** Reads the list generated at build time. */
fun interface LicencesSource {
    suspend fun load(): Licences
}

/**
 * The components of the list generated at build time, given as the text of its file.
 *
 * A component shows once: the list holds a multiplatform artifact and its twin for one platform as two
 * entries of the same name, author and licences, and only the first by id is kept.
 *
 * A text that cannot be read gives empty lists: the screen then shows no component, and nothing fails.
 * That is logged, so that a build whose list is broken can be diagnosed; the list is the build's own
 * file, and the log holds nothing of the user.
 */
fun licencesOf(json: String): Licences {
    val components = read(json)
        .map { it.toComponent() }
        .sortedBy { it.id }
        .distinctBy { Shown(it.name, it.author, it.bundled, it.licences) }
        .sortedBy { it.name.lowercase() }
    val (bundled, libraries) = components.partition { it.bundled }
    return Licences(bundled = bundled, libraries = libraries)
}

/**
 * The entries of the list, none when it cannot be read.
 *
 * The library keeps a parsing failure to itself and answers with an empty list, so an empty list is
 * logged as well as a failure it lets through: the generated list is never empty.
 */
private fun read(json: String): List<Library> {
    val libraries = runCatching { Libs.Builder().withJson(json).build().libraries }.getOrElse {
        Log.w(LogTag, "The list of licences cannot be read.", it)
        return emptyList()
    }
    if (libraries.isEmpty()) Log.w(LogTag, "The list of licences holds no component: empty, or not read.")
    return libraries
}

/** What a row shows of a component: two components alike in all of this are one row. */
private data class Shown(val name: String, val author: String?, val bundled: Boolean, val licences: List<Licence>)

private fun Library.toComponent() = Component(
    id = uniqueId,
    name = name,
    author = developers.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
        ?: organization?.name?.takeIf(String::isNotBlank),
    bundled = tag == BundledTag,
    licences = licenses.map { it.toLicence() },
)

private fun License.toLicence() = Licence(
    shortName = spdxId?.takeIf(String::isNotBlank) ?: name,
    name = name,
    text = licenseContent?.takeIf(String::isNotBlank),
)
