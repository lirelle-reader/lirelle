package app.tfdn.lirelle.feature.player.bookmarks

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.Tile
import app.tfdn.lirelle.feature.player.TileIconSize

internal const val BookmarkButtonTag = "player-bookmark"

/** "Bookmark", the fourth tile of the player's bottom row: a bookmark at the place playing. */
@Composable
internal fun BookmarkTile(onClick: () -> Unit) {
    Tile(stringResource(R.string.player_bookmark), onClick, Modifier.testTag(BookmarkButtonTag)) {
        Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(TileIconSize))
    }
}
