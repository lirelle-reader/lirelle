package app.tfdn.lirelle.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.ui.SectionHeading

private val RecentRowMinHeight = 48.dp
private val RecentRowShape = RoundedCornerShape(12.dp)
private val ChipTouchHeight = 48.dp
private val ChipMinHeight = 32.dp
private val ChipShape = RoundedCornerShape(8.dp)
private val ChipGap = 8.dp

/**
 * What the screen offers before anything is typed: the recent searches and the genres, each
 * left out when there is none.
 *
 * @param wide true for two zones side by side, genres then recent searches; false for one
 *   column, recent searches first. A zone with nothing leaves its width to the other.
 * @param onRun runs a recent search again.
 */
@Composable
internal fun SearchStart(
    recent: List<String>,
    genres: List<Genre>,
    wide: Boolean,
    onRun: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onOpenGenre: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (wide) {
        Row(modifier = modifier.fillMaxSize()) {
            if (genres.isNotEmpty()) {
                ScrollingZone(SearchMainTag, Modifier.weight(MainWeight)) { GenreBlock(genres, onOpenGenre) }
            }
            if (recent.isNotEmpty()) {
                ScrollingZone(SearchSideTag, Modifier.weight(SideWeight)) {
                    RecentBlock(recent, onRun, onRemove, onClear)
                }
            }
        }
    } else {
        ScrollingZone(tag = null, modifier = modifier.fillMaxSize()) {
            if (recent.isNotEmpty()) {
                Column(modifier = Modifier.testTag(SearchSideTag)) { RecentBlock(recent, onRun, onRemove, onClear) }
            }
            if (genres.isNotEmpty()) {
                Column(modifier = Modifier.testTag(SearchMainTag)) { GenreBlock(genres, onOpenGenre) }
            }
        }
    }
}

@Composable
private fun RecentBlock(
    recent: List<String>,
    onRun: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    SectionHeading(
        title = stringResource(R.string.search_recent),
        seeAllLabel = stringResource(R.string.search_recent_clear),
        onSeeAll = onClear,
        seeAllDescription = stringResource(R.string.search_recent_clear_description),
    )
    recent.forEach { entry -> RecentRow(entry, onRun = { onRun(entry) }, onRemove = { onRemove(entry) }) }
}

@Composable
private fun RecentRow(entry: String, onRun: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag("recent-$entry")
            .fillMaxWidth()
            .heightIn(min = RecentRowMinHeight)
            .clip(RecentRowShape)
            // A button: the text is a one-line label. The remove button stays an item of its own.
            .clickable(role = Role.Button, onClick = onRun),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.search_recent_remove, entry),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreBlock(genres: List<Genre>, onOpenGenre: (String) -> Unit) {
    SectionHeading(title = stringResource(R.string.search_genres))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
        genres.forEach { genre -> GenreChip(genre, onClick = { onOpenGenre(genre.name) }) }
    }
}

/**
 * A genre and its number of books when the server counts them, in the shape of a chip.
 *
 * It is not a Material chip, whose label is one line cut with an ellipsis: a genre is never cut.
 * A long name takes the width it needs, up to the whole row, then wraps; the count goes under it
 * when both do not fit on a line. The button is a layer under the texts rather than around
 * them: it is as large as the chip, says the name and the count in one sentence, and the texts
 * are not read a second time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChip(genre: Genre, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A server that does not count a genre's books gives no number: the name alone.
    val count = genre.bookCount
    val description = if (count == null) {
        genre.name
    } else {
        val books = pluralStringResource(R.plurals.search_book_count, count, count)
        stringResource(R.string.search_genre_description, genre.name, books)
    }
    val interactions = remember { MutableInteractionSource() }
    Box(modifier = modifier.heightIn(min = ChipTouchHeight), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag("genre-${genre.name}")
                // The touch target is taller than the chip drawn; the ripple is on the chip.
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        )
        FlowRow(
            modifier = Modifier
                .heightIn(min = ChipMinHeight)
                .clip(ChipShape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), ChipShape)
                .indication(interactions, ripple())
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = genre.name,
                modifier = Modifier.semantics { hideFromAccessibility() },
                style = MaterialTheme.typography.labelLarge,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.semantics { hideFromAccessibility() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
