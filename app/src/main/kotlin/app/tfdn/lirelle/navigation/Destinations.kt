package app.tfdn.lirelle.navigation

import kotlinx.serialization.Serializable

// Sign-in level
@Serializable data object ServerDestination

@Serializable data object SignInDestination

/**
 * The page of a provider that signs in through a WebView of the app, over the sign-in step: the step holds it,
 * and the route carries nothing of it.
 */
@Serializable data object ProviderPageDestination

// Root level: the shell, the full-screen readers (of books, of comics), the player and its settings, and the
// sign-in screens shown over the current profile
@Serializable data object ShellDestination

/**
 * The sign-in screens over the current profile.
 *
 * @property profileId the profile that signs in again; null to add a profile.
 */
@Serializable data class SignInOverProfileDestination(val profileId: String? = null)

/**
 * @property bookId the book's id, as its text: a route carries no id of the app's own.
 * @property fromStart the book is read again: it opens at its beginning, not at its position.
 * @property narrate the book's narration starts from the page it opens at, unless it is loaded already.
 */
@Serializable
data class ReaderDestination(val bookId: String, val fromStart: Boolean = false, val narrate: Boolean = false)

/**
 * The comics reader, on a book that is a comic.
 *
 * @property bookId the album's id, as its text.
 * @property fromStart the album is read again: it opens at its first page, not at its position.
 */
@Serializable
data class ComicsDestination(val bookId: String, val fromStart: Boolean = false)

/** The player of the book loaded: there is one player, so the route carries no book. */
@Serializable data object PlayerDestination

@Serializable data object PlayerSettingsDestination

// Shell level: one graph per top-level destination, so each keeps its own back stack
@Serializable data object HomeGraph

@Serializable data object HomeDestination

@Serializable data object LibraryGraph

@Serializable data object LibraryDestination

// A route carries an id as its text: a route's arguments are the types Navigation knows. The screen it opens
// wraps the text back into the id.

@Serializable data class SeriesDestination(val id: String)

@Serializable data class AuthorDestination(val id: String)

/** A collection or a smart list. The name is carried so the title shows at once. */
@Serializable data class ShelfDestination(val smartList: Boolean, val id: String, val name: String)

@Serializable data class BookDestination(val id: String)

@Serializable data class GenreDestination(val name: String)

/** @property kind the name of a `HomeRowKind`. */
@Serializable data class HomeListDestination(val kind: String)

@Serializable data object DownloadsGraph

@Serializable data object DownloadsDestination

@Serializable data object MoreGraph

@Serializable data object MoreDestination

@Serializable data object AccountDestination

@Serializable data object HistoryDestination

/**
 * One read of a book, by its id's text. The book is carried, by its id's text too, so the screen can lead to it;
 * the history screen may not know it.
 */
@Serializable data class ReadDetailDestination(val readId: String, val bookId: String?)

/** The settings: their list and the page chosen from it, which the screen itself holds. */
@Serializable data object SettingsDestination

/** About and, from it, the licences, which the screen itself holds. */
@Serializable data object AboutDestination

@Serializable data object SearchGraph

@Serializable data object SearchDestination

@Serializable data class SearchBooksDestination(val text: String)

@Serializable data class SearchSeriesDestination(val text: String)

@Serializable data class SearchAuthorsDestination(val text: String)
