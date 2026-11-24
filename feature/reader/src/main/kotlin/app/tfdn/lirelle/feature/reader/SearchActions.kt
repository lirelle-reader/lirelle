package app.tfdn.lirelle.feature.reader

/** The search in the book: its sheet, and its bar on the page. Nothing is searched before the book is shown. */
interface SearchActions {
    /** Opens the search's sheet: empty, or with the query and the results it was left with. */
    fun openSearch()

    /** What the field holds, cut at 200 characters: searched after 400 ms without typing, from 2 characters on. */
    fun editQuery(query: String)

    /** Searches what the field holds at once, from 2 characters on. */
    fun submitQuery()

    /**
     * Closes the sheet and goes to the result at [index] of search [searchId], counted across every section's
     * hits; nothing when that search is no longer the one shown. "Go back" returns to where the search started,
     * however many results are shown.
     */
    fun showResult(searchId: Int, index: Int)

    /** The result after the current one, the first after the last. */
    fun nextResult()

    /** The result before the current one, the last before the first. */
    fun previousResult()

    /** The bar's list button: the sheet again, as it was. */
    fun backToResults()

    /** Removes the outlines from the page and forgets the search. */
    fun closeSearch()
}
