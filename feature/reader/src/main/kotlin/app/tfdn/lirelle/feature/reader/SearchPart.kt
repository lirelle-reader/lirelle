package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.SearchHit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long typing pauses before what was typed is searched. */
private val TypingPause = 400.milliseconds

/** The shortest query searched, and the longest one the field holds, in characters as the page counts them. */
private const val MinQueryLength = 2
internal const val MaxQueryLength = 200

private val NewSearch = SearchUi(
    query = "",
    searchId = null,
    running = false,
    fraction = 0.0,
    groups = emptyList(),
    total = 0,
    truncated = false,
    current = null,
    onPage = false,
)

/** The search in the book, as the user drives it. What the page finds is in [run]. */
internal class SearchPart(private val core: ReaderCore) : SearchActions {
    val run = SearchRun(core)

    /** The search waiting for typing to pause. */
    private var pending: Job? = null

    /** A result was shown since the search was opened: "Go back" already leads to where it started. */
    private var shown = false

    override fun openSearch() = core.update { it.copy(openSheet = ReaderSheet.Search, search = it.search ?: NewSearch) }

    /** A query too short to be searched clears the search there was: the results no longer match the field. */
    override fun editQuery(query: String) {
        val search = core.current.search ?: return
        val text = query.cutToCodePoints(MaxQueryLength)
        pending?.cancel()
        if (text.trim().codePointLength() < MinQueryLength) {
            if (search.searchId != null) {
                run.forget()
                core.driver.send(HostMessage.ClearSearch)
            }
            core.update { it.copy(search = NewSearch.copy(query = text)) }
            return
        }
        core.update { it.copy(search = it.search?.copy(query = text)) }
        pending = core.scope.launch {
            delay(TypingPause)
            submitQuery()
        }
    }

    override fun submitQuery() {
        pending?.cancel()
        core.current.search?.let { run.start(it.query) }
    }

    override fun showResult(searchId: Int, index: Int) {
        if (core.current.search?.searchId == searchId) show(index)
    }

    /** What was typed meanwhile is not searched: the user went to a result instead. */
    private fun show(index: Int) {
        val hit = core.current.search?.hitAt(index) ?: return
        if (!core.driver.opened) return
        pending?.cancel()
        // Once "Go back" leads to where the search started, results lead nowhere new; unless the user read on.
        core.jump(HostMessage.ShowSearchResult(hit.cfi), remember = !shown || core.goBack.isEmpty)
        shown = true
        core.update {
            it.copy(openSheet = null, controlsShown = false, search = it.search?.copy(current = index, onPage = true))
        }
    }

    override fun nextResult() {
        core.current.search?.stepped(by = 1)?.let(::show)
    }

    override fun previousResult() {
        core.current.search?.stepped(by = -1)?.let(::show)
    }

    override fun backToResults() {
        pending?.cancel()
        if (core.current.search != null) core.update { it.copy(openSheet = ReaderSheet.Search) }
    }

    override fun closeSearch() {
        pending?.cancel()
        if (core.current.search == null) return
        run.forget()
        shown = false
        core.driver.send(HostMessage.ClearSearch)
        core.update { state ->
            state.copy(search = null, openSheet = state.openSheet.takeUnless { it == ReaderSheet.Search })
        }
    }

    /** "Search" on a selection: the search opens with its text, searched at once. */
    fun searchFor(text: String) {
        openSearch()
        editQuery(text.trim())
        submitQuery()
    }
}

/**
 * The search the page runs, and what it finds. Only the messages of the last search started are
 * taken: those of an older one, still on their way, are dropped.
 */
internal class SearchRun(private val core: ReaderCore) {
    private var lastId = 0

    /** The query the page was last asked to search, until the search is closed. */
    private var searched: String? = null

    /** A query of 2 characters (code points) or more, unless it is the one searched already. */
    fun start(query: String) {
        val text = query.trim()
        if (text.codePointLength() < MinQueryLength || text == searched) return
        val id = lastId + 1
        if (!core.driver.send(HostMessage.Search(id, text))) return
        lastId = id
        searched = text
        core.update { state ->
            state.copy(search = state.search?.let { NewSearch.copy(query = it.query, searchId = id, running = true) })
        }
    }

    /** A page that opened the book again has forgotten the search: it is asked again. */
    fun onOpened() {
        val query = searched ?: return
        searched = null
        start(query)
    }

    fun forget() {
        searched = null
    }

    fun onMessage(message: PageMessage.Finding) = when (message) {
        is PageMessage.SearchResults -> onResults(message)
        is PageMessage.SearchProgress -> change(message.searchId) { it.copy(fraction = message.fraction) }
        is PageMessage.SearchDone -> change(message.searchId) {
            it.copy(running = false, fraction = 1.0, total = message.total, truncated = message.truncated)
        }
    }

    /** A section whose hits went on from the last message goes on in the last row. */
    private fun onResults(message: PageMessage.SearchResults) = change(message.searchId) { search ->
        val rows = search.groups.toMutableList()
        message.groups.forEach { group ->
            val last = rows.lastOrNull()
            if (last?.sectionIndex == group.sectionIndex) {
                rows[rows.lastIndex] = last.copy(hits = last.hits + group.results)
            } else {
                rows += SearchRow(group.sectionIndex, group.label, group.results)
            }
        }
        search.copy(
            groups = rows,
            total = search.total + message.groups.sumOf { it.results.size },
            fraction = message.fraction ?: search.fraction,
        )
    }

    private fun change(searchId: Int, transform: (SearchUi) -> SearchUi) = core.update { state ->
        val search = state.search?.takeIf { it.searchId == searchId } ?: return@update state
        state.copy(search = transform(search))
    }
}

/**
 * No sheet open. The search's sheet closed while the page outlines results, or still searches,
 * leaves the search's bar on the page, without a current result: the outlines can be removed.
 */
internal fun ReaderUiState.withoutSheet(): ReaderUiState {
    val closing = search?.takeIf { openSheet == ReaderSheet.Search }
    val outlined = closing?.searchId != null && (closing.running || closing.groups.isNotEmpty())
    return copy(openSheet = null, search = if (outlined) closing?.copy(onPage = true) else search)
}

/** Characters as the page counts them: an emoji is one, not two units of UTF-16. */
private fun String.codePointLength() = codePointCount(0, length)

/** At most [max] characters as the page counts them; a surrogate pair is never split. */
private fun String.cutToCodePoints(max: Int): String =
    if (codePointLength() <= max) this else substring(0, offsetByCodePoints(0, max))

/** The hit at [index] across every row; null outside them. */
private fun SearchUi.hitAt(index: Int): SearchHit? =
    if (index < 0) null else groups.asSequence().flatMap { it.hits }.elementAtOrNull(index)

/** The index [by] results away from the current one, wrapping around; from before the first when none is. */
private fun SearchUi.stepped(by: Int): Int? {
    val count = groups.sumOf { it.hits.size }
    if (count == 0) return null
    val from = current ?: if (by > 0) -1 else count
    return Math.floorMod(from + by, count)
}
