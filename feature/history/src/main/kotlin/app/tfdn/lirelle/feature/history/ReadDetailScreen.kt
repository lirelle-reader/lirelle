package app.tfdn.lirelle.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.ui.BookCover

private val ContentMaxWidth = 600.dp
private val CoverWidth = 96.dp
private const val StackedFontScale = 1.5f

@Composable
fun ReadDetailRoute(
    onOpenBook: (bookId: BookId) -> Unit,
    onBack: () -> Unit,
    viewModel: ReadDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReadDetailScreen(
        state = state,
        onSetStars = viewModel::setStars,
        onOpenBook = onOpenBook,
        onBack = onBack,
        onRetryMarks = viewModel::retryMarks,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadDetailScreen(
    state: ReadDetailUiState,
    onSetStars: (Int) -> Unit,
    onOpenBook: (bookId: BookId) -> Unit,
    onBack: () -> Unit,
    onRetryMarks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.read_detail_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BookHeader(state.record)
                (state.rating as? RatingUi.Editable)?.let { RatingRow(it, onSetStars) }
                state.record?.let { MetadataGrid(it, state.capabilities) }
                MarksSections(state.marks, onRetryMarks)
                state.openBookId?.let { bookId ->
                    Button(onClick = { onOpenBook(bookId) }, modifier = Modifier.fillMaxWidth()) {
                        WrappingLabel(stringResource(R.string.read_detail_open_book))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookHeader(record: ReadRecord?) {
    // At a large text size the title would not fit beside the cover: it goes below it.
    val stacked = LocalDensity.current.fontScale >= StackedFontScale
    val cover = @Composable {
        BookCover(
            url = record?.detailCoverUrl,
            title = record?.title.orEmpty(),
            shape = CoverShape.Portrait,
            modifier = Modifier.width(CoverWidth),
            showTitleWhenMissing = false,
        )
    }
    val details = @Composable { modifier: Modifier -> BookDetails(record, modifier) }
    if (stacked) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cover()
            details(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cover()
            details(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BookDetails(record: ReadRecord?, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = record?.title ?: stringResource(R.string.read_detail_untitled),
            style = MaterialTheme.typography.titleLarge,
        )
        record?.authors?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = it.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        record?.let {
            Text(
                text = outcomeLabel(it.outcome),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (record?.removed == true) {
            Text(
                text = stringResource(R.string.read_detail_removed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RatingRow(rating: RatingUi.Editable, onSetStars: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            repeat(MaxStars) { index ->
                val value = index + 1
                IconButton(onClick = { onSetStars(value) }, enabled = !rating.saving) {
                    Icon(
                        imageVector = if (value <= (rating.stars ?: 0)) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.read_detail_rate_star, value),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.read_detail_rate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rating.error) {
            Text(
                text = stringResource(R.string.read_detail_rate_error),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
