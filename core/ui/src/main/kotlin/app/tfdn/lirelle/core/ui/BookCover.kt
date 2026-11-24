package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.CoverShape
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

private val CoverCorner = RoundedCornerShape(6.dp)

/**
 * A book cover at its own aspect ratio. The box has its final size before the image loads,
 * so lists do not jump. [url] null means the book has no cover: nothing is requested and
 * the title is shown on a plain tile.
 *
 * @param showTitleWhenMissing false leaves the tile of a book without a cover empty, where
 *   the title is already next to it or there is no title yet.
 */
@Composable
fun BookCover(
    url: String?,
    title: String,
    shape: CoverShape,
    modifier: Modifier = Modifier,
    showTitleWhenMissing: Boolean = true,
) {
    Box(
        modifier = modifier
            .aspectRatio(shape.widthOverHeight)
            .clip(CoverCorner)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.BottomStart,
    ) {
        when {
            url != null -> AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = LocalLirelleImageLoader.current ?: SingletonImageLoader.get(LocalPlatformContext.current),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            showTitleWhenMissing -> Text(
                text = title,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
