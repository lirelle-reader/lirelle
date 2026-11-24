package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult

/** Something a screen loads in one call. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Failed(val error: ApiError) : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

fun <T> ApiResult<T>.toLoadable(): Loadable<T> = when (this) {
    is ApiResult.Success -> Loadable.Ready(value)
    is ApiResult.Failure -> Loadable.Failed(error)
}

const val LoadingTag = "loading"

/**
 * Shows [state]: the [loading] slot, the failure with a retry (see [FailedState]), or [content].
 *
 * @param loading lists pass placeholder items here; the default is a centred spinner.
 */
@Composable
fun <T> LoadableContent(
    state: Loadable<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { CenteredSpinner() },
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            Loadable.Loading -> Box(modifier = Modifier.testTag(LoadingTag)) { loading() }
            is Loadable.Failed -> FailedState(error = state.error, onRetry = onRetry, scrolls = true)
            is Loadable.Ready -> content(state.value)
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
