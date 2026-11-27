package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.source.kavita.reading.ReadingProfileDto

/**
 * The direction a series' pages are turned in, from its reading [profile] (null when the server has none to give,
 * which says nothing). Kavita's default profile says left to right whatever the library, so on a Manga library
 * ([libraryType] 0) it reads right to left; the profile of a user or of the series says what it says.
 */
internal fun directionOf(profile: ReadingProfileDto?, libraryType: Int): PageDirection? = when {
    profile == null -> null
    profile.kind == DefaultProfileKind && libraryType == MangaLibraryType -> PageDirection.RightToLeft
    profile.readingDirection == RightToLeftValue -> PageDirection.RightToLeft
    else -> PageDirection.LeftToRight
}

private const val DefaultProfileKind = 0
private const val MangaLibraryType = 0
private const val RightToLeftValue = 1
