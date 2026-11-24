package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Calls [action] each time the screen becomes the one in front: first shown, returned to, app back in front;
 * and [onHidden] each time it stops being so: another screen over it, the app behind another, the screen off.
 */
@Composable
fun OnShown(action: () -> Unit, onHidden: () -> Unit = {}) {
    val current by rememberUpdatedState(action)
    val hidden by rememberUpdatedState(onHidden)
    LifecycleResumeEffect(Unit) {
        current()
        onPauseOrDispose { hidden() }
    }
}
