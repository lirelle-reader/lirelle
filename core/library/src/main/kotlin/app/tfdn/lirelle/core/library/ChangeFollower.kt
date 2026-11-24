package app.tfdn.lirelle.core.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Follows [ReadingChanges] for a screen. A change that comes while the screen is in front reloads it at
 * once; one that comes while it is not (in the back stack, behind another app, under a screen that is
 * off) only marks it old, and it is reloaded once, when it is shown again. Several changes meanwhile are
 * one reload.
 *
 * @param idle waits until the screen loads nothing: a load under way may have asked before the change.
 * @param reload reloads the screen quietly.
 */
class ChangeFollower(
    private val changes: ReadingChanges,
    private val scope: CoroutineScope,
    private val idle: suspend () -> Unit,
    private val reload: () -> Unit,
) {
    private var shown = false
    private var missed = false

    /** Follows the changes after [known], the version read before the screen's first load. */
    fun start(known: Long): Job = scope.launch {
        var followed = known
        changes.version.collect { version ->
            // Already covered by a reload that started after it.
            if (version <= followed) return@collect
            if (!shown) {
                missed = true
                followed = version
                return@collect
            }
            idle()
            followed = changes.version.value
            reload()
        }
    }

    /** The screen is in front. True when it reloads for a change it missed meanwhile. */
    fun shown(): Boolean {
        shown = true
        if (!missed) return false
        missed = false
        scope.launch {
            idle()
            reload()
        }
        return true
    }

    /** The screen is no longer in front. */
    fun hidden() {
        shown = false
    }
}
