package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.source.kavita.library.dto.GenreDto
import app.tfdn.lirelle.source.kavita.library.dto.PersonRefDto

// How the names Kavita gives are read: its people's, its genres', any text it may leave blank.

/** The authors of a book: its writers, then its pencillers, each once. */
internal fun authorNames(writers: List<PersonRefDto>, pencillers: List<PersonRefDto>): List<String> =
    (writers + pencillers).map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()

/** The first name of these people, a publisher's; null when none has one. */
internal fun List<PersonRefDto>.firstName(): String? = firstNotNullOfOrNull { it.name.orNullIfBlank() }

/** The genres' titles, in Kavita's order. */
internal fun List<GenreDto>.titles(): List<String> = mapNotNull { it.title.orNullIfBlank() }

/** The text without its surrounding spaces; null when nothing is left, or there was none. */
internal fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
