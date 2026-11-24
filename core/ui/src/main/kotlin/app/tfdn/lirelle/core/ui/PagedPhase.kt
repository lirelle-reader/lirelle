package app.tfdn.lirelle.core.ui

import app.tfdn.lirelle.core.network.ApiError

/** What a paged list has to show in the place of its items. */
sealed interface PagedPhase {
    /** The first load is running: placeholders. */
    data object Loading : PagedPhase

    /** The first load failed and there is nothing to show. */
    data class Failed(val error: ApiError) : PagedPhase

    /** Loaded, and the list is empty. */
    data object Empty : PagedPhase

    /** There are items, whatever is loading or failed around them. */
    data object Loaded : PagedPhase
}
