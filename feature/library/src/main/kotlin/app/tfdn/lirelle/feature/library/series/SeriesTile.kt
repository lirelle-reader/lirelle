package app.tfdn.lirelle.feature.library.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.feature.library.R

private const val MaxStackedCovers = 3

// The corner of a book cover.
private val BlankCoverCorner = RoundedCornerShape(6.dp)

@Composable
internal fun SeriesTile(series: Series, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .testTag("series-${series.id}")
            // A button: the texts under the covers are one-line labels, and are read as one item.
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StackedCovers(series)
        Text(
            text = series.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (series.authors.isNotEmpty()) {
            Text(
                text = series.authors.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.series_progress, series.readCount, series.bookCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { series.progress },
            modifier = Modifier.fillMaxWidth(),
            // No dot at the end of the track: on an unread series it reads as progress.
            drawStopIndicator = {},
        )
    }
}

/** Up to three covers, the first in front on the left, the others behind and smaller. */
@Composable
private fun StackedCovers(series: Series) {
    val covers = series.coverUrls.take(MaxStackedCovers)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(StackAspectRatio),
        contentAlignment = Alignment.BottomStart,
    ) {
        val step = maxWidth * StackStep
        // The name is written under the covers, so a series without covers gets a plain tile.
        if (covers.isEmpty()) BlankCover()
        covers.indices.reversed().forEach { index ->
            BookCover(
                url = covers[index],
                title = series.name,
                shape = CoverShape.Portrait,
                modifier = Modifier
                    .padding(start = step * index, top = 8.dp * index)
                    .fillMaxHeight(),
            )
        }
    }
}

// Width over height of the stack: a 2:3 cover takes 58 % of the width, two steps of 17 % more.
private const val StackAspectRatio = 1.15f
private const val StackStep = 0.17f

/** The place of a cover, with nothing on it. */
@Composable
private fun BlankCover() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(CoverShape.Portrait.widthOverHeight)
            .clip(BlankCoverCorner)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
internal fun SeriesPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(StackAspectRatio),
    ) { BlankCover() }
}
