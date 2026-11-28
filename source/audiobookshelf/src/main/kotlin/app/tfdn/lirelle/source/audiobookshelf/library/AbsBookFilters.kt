package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto

/**
 * What narrows a library's books on the device, since Audiobookshelf's own filters take one value at a time and know
 * nothing of formats as Lirelle groups them: the read [states] (several add up), the [formats] (several add up), a
 * [text] looked for in the title, subtitle, authors, narrators and series, and, with any of those, the [genre], its
 * name and the book's trimmed. A genre alone narrows nothing here: the server lists a genre's books by itself.
 *
 * Audiobookshelf keeps no "want to read" and no "abandoned" state and aligns no narration with a text: those are
 * never offered for it, and are left out when asked all the same.
 */
internal class AbsBookFilters private constructor(
    private val states: Set<ReadState>,
    private val formats: Set<FormatFilter>,
    private val text: AbsText?,
    private val genre: String?,
) {

    /** Whether the list is narrowed on the device: a status, a format or a text is asked. */
    val isActive: Boolean get() = states.isNotEmpty() || formats.isNotEmpty() || text != null

    /** Whether [item], read by the account as [readings] say, is one of the books asked. */
    fun keeps(item: AbsItemDto, readings: AbsReadings): Boolean {
        val metadata = item.media.metadata
        return (states.isEmpty() || readings.state(item.id) in states) &&
            (formats.isEmpty() || AbsFormats.of(item).any { it in formats }) &&
            (genre == null || item.hasGenre(genre)) &&
            (text == null || metadata.let {
                text.isIn(it.title, it.subtitle, it.authorName, it.narratorName, it.seriesName)
            })
    }

    companion object {
        /** What [request] narrows the books to. */
        fun of(request: BookListRequest): AbsBookFilters = AbsBookFilters(
            states = request.filters.statuses.mapNotNull { it.readState }.toSet(),
            formats = request.filters.formats,
            text = AbsText.of(request.text),
            genre = request.genre?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** The read state a status stands for on Audiobookshelf; null for one it does not keep. */
private val StatusFilter.readState: ReadState?
    get() = when (this) {
        StatusFilter.Unread -> ReadState.Unread
        StatusFilter.Reading -> ReadState.Reading
        StatusFilter.Read -> ReadState.Read
        StatusFilter.WantToRead, StatusFilter.Abandoned -> null
    }
