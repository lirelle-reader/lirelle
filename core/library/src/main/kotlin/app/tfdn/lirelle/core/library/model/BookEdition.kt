package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId

/** What an edition is made of, as its line says it. */
enum class EditionMedia { Text, Audio, TextAndAudio, Comic }

/** Where the reader is in an edition. */
sealed interface EditionProgress {
    data object NotStarted : EditionProgress

    /** @property percent 1 to 99: of the medium used most recently, as the server tells it. */
    data class InProgress(val percent: Int) : EditionProgress

    data object Finished : EditionProgress

    /** The server does not say: it knows linked books, not yet editions. */
    data object Unknown : EditionProgress
}

/**
 * One edition of a work — the ebook, a translation, another printing — among those of [BookDetail.editions].
 *
 * @property isOpen the edition whose page lists them.
 * @property label what sets it apart ("Full cast", "Scribner · 2011"); null when its server gives nothing to
 *   name it by, and its [title] then stands for it.
 * @property coverUrl a small cover, absolute.
 */
data class BookEdition(
    val bookId: BookId,
    val isOpen: Boolean,
    val label: String?,
    val title: String,
    val coverUrl: String?,
    val media: EditionMedia,
    val progress: EditionProgress,
)
