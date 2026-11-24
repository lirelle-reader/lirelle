package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the Downloads destination asks of the updates: to fetch one, to fetch them all, and to look for new ones.
 *
 * A refusal is not said: an account that may not download is offered no update.
 */
class UpdateRequests internal constructor(private val scope: CoroutineScope, private val updates: DownloadUpdates) {
    private val checking = MutableStateFlow(false)

    /** True while the check asked by [refresh] runs. */
    val refreshing: StateFlow<Boolean> = checking

    fun update(bookId: BookId) {
        scope.launch { updates.update(bookId) }
    }

    fun updateAll() {
        scope.launch { updates.updateAll() }
    }

    /** Checks every kept book now, whatever the age of the last check; without a network it ends at once. */
    fun refresh() {
        if (!checking.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                updates.check(force = true)
            } finally {
                checking.value = false
            }
        }
    }
}
