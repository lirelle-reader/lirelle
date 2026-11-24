package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.network.ApiError

const val PagingErrorBarTag = "paging-error"

/**
 * The frame of every paged list: pull to refresh, and a bar pinned under the list when a load
 * failed while items are on screen.
 *
 * The bar is not an item of the list: the list has a placeholder for every item the server
 * counts, so an item placed after them is hundreds of rows away from the reader.
 *
 * @param errorOf turns a failed load into the error to show.
 * @param content the list, drawn for the given phase. It is called from one place whatever
 *   the phase, so what it remembers (an open menu in its header) survives a reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> PagedListScaffold(
    items: LazyPagingItems<T>,
    errorOf: (Throwable) -> ApiError,
    modifier: Modifier = Modifier,
    content: @Composable (PagedPhase) -> Unit,
) {
    val refresh = items.loadState.refresh
    val hasItems = items.itemCount > 0
    val phase = when {
        hasItems -> PagedPhase.Loaded
        refresh is LoadState.Error -> PagedPhase.Failed(errorOf(refresh.error))
        refresh is LoadState.Loading -> PagedPhase.Loading
        else -> PagedPhase.Empty
    }
    val pageFailure = (items.loadState.append as? LoadState.Error ?: items.loadState.prepend as? LoadState.Error)
    val pageFailed = pageFailure != null
    // A page out of reach while the profile waits for a sign-in is out of reach for that: "Try again" cannot help.
    val pageWaitsForSignIn = pageFailure != null && errorOf(pageFailure.error).isNoNetwork && signInWait() != null
    PullToRefreshBox(
        isRefreshing = refresh is LoadState.Loading && hasItems,
        onRefresh = items::refresh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { content(phase) }
            when {
                // The items on screen are those of before: the reload they were asked for failed.
                hasItems && refresh is LoadState.Error -> ServerFailureBar(errorOf(refresh.error), items::retry)
                pageWaitsForSignIn -> ServerFailureBar(ApiError.ServerUnreachable, items::retry)
                pageFailed -> PagingErrorBar(stringResource(R.string.list_page_error), onRetry = items::retry)
            }
        }
    }
}

/**
 * A failed load, said under the list with its retry.
 *
 * The button goes under the message when both do not fit on one line, so neither is squeezed.
 */
@Composable
fun PagingErrorBar(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, canRetry: Boolean = true) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PagingErrorBarTag),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (canRetry) {
                TextButton(onClick = onRetry) { SingleLineLabel(stringResource(R.string.state_retry)) }
            }
        }
    }
}
