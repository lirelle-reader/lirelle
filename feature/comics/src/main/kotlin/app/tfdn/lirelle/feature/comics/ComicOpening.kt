package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.DocumentFailure
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What the opening hands the album to, once it is open. */
internal class OpenedParts(val settings: SettingsPart, val turning: Turning, val bars: Bars)

/**
 * From a book's id to an album open at its position: the book's details, its file read by pages (a comic, a
 * PDF, a DjVu), the page count, the settings of its series, the position. A step that fails is said, and the
 * whole is tried again. A book the device keeps whole needs no server: its page is the one its download kept
 * when the server cannot be reached or no longer has the book, and its file, or its pages, are the kept ones.
 *
 * @param lasting where what is written on leaving runs: it outlives the view model.
 */
internal class ComicOpening(
    private val core: ComicCore,
    private val services: ComicReaderServices,
    private val lasting: CoroutineScope,
    private val fromStart: Boolean,
    private val parts: OpenedParts,
) {
    private var opening: Job? = null

    /** Opens the album; again, after a failure. */
    fun start() {
        opening?.cancel()
        core.update { it.copy(phase = ComicPhase.Loading) }
        opening = core.scope.launch { open()?.let(::fail) }
    }

    private fun fail(reason: ComicFailure) = core.update { it.copy(phase = ComicPhase.Failed(reason)) }

    /** Why the album could not be opened; null when it is open. */
    private suspend fun open(): ComicFailure? {
        val detail = when (val answer = services.books.detail(core.bookId)) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return ComicFailure.Load(answer.error)
        }
        val scope = found(detail)
        val file = detail.pagedFile ?: return ComicFailure.NoComic
        return when (val answer = countOf(pagesOf(detail, file))) {
            is PageCountResult.Known -> show(file, scope, directionOf(detail.pageDirection), answer.count)
            is PageCountResult.Failed -> ComicFailure.Load(answer.error)
            is PageCountResult.Unreadable -> answer.failure.said()
        }
    }

    /**
     * The page count of [pages]. A document drawn on the device is fetched whole for it: the state says how
     * far that is while it lasts, and no longer after it.
     */
    private suspend fun countOf(pages: ComicPages): PageCountResult = coroutineScope {
        val following = launch { pages.opening.collect { progress -> core.update { it.copy(opening = progress) } } }
        try {
            pages.pageCount()
        } finally {
            following.cancel()
            core.update { it.copy(opening = null) }
        }
    }

    private fun DocumentFailure.said(): ComicFailure = when (this) {
        DocumentFailure.Protected -> ComicFailure.Protected
        DocumentFailure.Damaged -> ComicFailure.Damaged
        DocumentFailure.KeptDamaged -> ComicFailure.KeptDamaged
    }

    /**
     * The album has [count] pages: it is shown at its position, with the settings kept for [scope], its
     * direction the [default] its server gives as long as the user chose none. One without a page has nothing
     * to read, which is what is given back.
     */
    private suspend fun show(file: BookFile, scope: DirectionScope, default: Direction?, count: Int): ComicFailure? {
        if (count <= 0) return ComicFailure.NoComic
        parts.settings.follow(scope, default)
        val restored = sessionOf(file).restore(fromStart)
        core.page = pageIndexAt(restored.page, restored.percent, count)
        core.update { it.copy(phase = ComicPhase.Ready, pageCount = count, forward = true) }
        core.layout()
        // Where the album opens is not a page turned: nothing is written for it.
        parts.turning.arrived(moved = false)
        parts.bars.open()
        services.books.opened(core.bookId)
        return null
    }

    /**
     * The book's details are known: what the bar says of it, what its pages are, and what its reading direction
     * is kept for.
     */
    private fun found(detail: BookDetail): DirectionScope {
        val series = detail.series
        val scope = series?.id?.let { DirectionScope.Series(it) } ?: DirectionScope.Book(detail.id)
        core.seriesId = series?.id
        core.update {
            it.copy(
                title = detail.title,
                kind = detail.pagedKind ?: it.kind,
                series = series?.let { s -> SeriesLine(s.name, s.index) },
                directionScope = scope,
            )
        }
        return scope
    }

    /**
     * The album's pages are opened once, from the source their kind needs, and their zones with them: a new try
     * asks the same ones.
     */
    private fun pagesOf(detail: BookDetail, file: BookFile): ComicPages = core.pages ?: run {
        val key = ComicKey(file.id, file.contentVersion, detail.pagedKind ?: PagedKind.Comic, detail.id)
        services.openers.pages.open(key, core.scope).also {
            core.pages = it
            core.panels = services.openers.panels.open(key, it, core.scope)
        }
    }

    private fun sessionOf(file: BookFile): ComicSession = core.session ?: ComicSession(
        fileId = file.id,
        positions = services.positions.create(file.id, core.scope),
        clock = services.clocks.create(),
        outbox = services.outbox,
        kick = services.flusher::kick,
        lasting = lasting,
    ).also { core.session = it }
}

/** The reader's direction for the way a server says pages turn; none when it says none. */
private fun directionOf(direction: PageDirection?): Direction? = when (direction) {
    PageDirection.LeftToRight -> Direction.LeftToRight
    PageDirection.RightToLeft -> Direction.RightToLeft
    PageDirection.Vertical -> Direction.Vertical
    null -> null
}
