package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

/** The height of the initials as a part of the circle's diameter: two wide capitals stay inside the disc. */
private const val InitialsOverDiameter = 0.32f

/**
 * The initials of a name: the first letter of its first word and of its last word, upper-cased.
 * A name of one word has one initial, a blank name none.
 */
internal fun initialsOf(name: String): String {
    val words = name.split(' ', '\t', '\n', ' ').filter { it.isNotBlank() }
    return listOfNotNull(words.firstOrNull(), words.lastOrNull().takeIf { words.size > 1 })
        .joinToString("") { word -> word.substring(0, word.offsetByCodePoints(0, 1)).uppercase() }
}

/** A round portrait; an author without one gets a circle with their initials. */
@Composable
fun AuthorPortrait(url: String?, name: String, modifier: Modifier = Modifier) {
    val initials = initialsOf(name)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // The initials stay under the image, so a portrait that fails to load is not a blank disc.
        // They are not announced: the item that holds the portrait says the name.
        if (initials.isNotEmpty()) {
            // Decoration sized to the disc, not to the system text size: at a large text size
            // initials that grew would be cut by the circle. `toSp` cancels the font scale.
            val fontSize = with(LocalDensity.current) { (maxWidth * InitialsOverDiameter).toSp() }
            Text(
                text = initials,
                modifier = Modifier.semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = fontSize,
                    lineHeight = TextUnit.Unspecified,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = LocalLirelleImageLoader.current ?: SingletonImageLoader.get(LocalPlatformContext.current),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
