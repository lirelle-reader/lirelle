package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first

/**
 * In the place of a screen the source does not offer, reached by an old link (a saved route, a deep link): an
 * empty page with its back button, which goes back by itself once it is in front (spec 2c, "Errors"). It waits
 * for that: the way back of a screen is only taken while it is the one in front.
 */
@Composable
fun NotOffered(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        back()
    }
    DetailScaffold(title = null, onBack = onBack, modifier = modifier) {}
}
