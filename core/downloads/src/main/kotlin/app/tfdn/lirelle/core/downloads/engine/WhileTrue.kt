package app.tfdn.lirelle.core.downloads.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs [work] for as long as [condition] holds. Null when it stopped holding: [work] was then cancelled, and
 * has ended when this returns.
 */
internal suspend fun <T> whileTrue(condition: Flow<Boolean>, work: suspend () -> T): T? = coroutineScope {
    val running = async { work() }
    val watcher = launch {
        condition.first { !it }
        running.cancel()
    }
    try {
        running.await()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } finally {
        watcher.cancel()
    }
}
