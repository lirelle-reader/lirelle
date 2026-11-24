package app.tfdn.lirelle.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import app.tfdn.lirelle.R
import app.tfdn.lirelle.navigation.DownloadsDestination
import app.tfdn.lirelle.navigation.DownloadsGraph
import app.tfdn.lirelle.navigation.HomeDestination
import app.tfdn.lirelle.navigation.HomeGraph
import app.tfdn.lirelle.navigation.LibraryDestination
import app.tfdn.lirelle.navigation.LibraryGraph
import app.tfdn.lirelle.navigation.MoreDestination
import app.tfdn.lirelle.navigation.MoreGraph
import app.tfdn.lirelle.navigation.SearchDestination
import app.tfdn.lirelle.navigation.SearchGraph

/**
 * The five entries of the navigation bar or rail, in display order.
 *
 * @property graph the navigation graph that holds the destination's own back stack.
 * @property root the first screen of that graph.
 */
enum class TopLevelDestination(
    val graph: Any,
    val root: Any,
    @StringRes val label: Int,
    val icon: ImageVector,
    val testTag: String,
) {
    Home(HomeGraph, HomeDestination, R.string.destination_home, Icons.Outlined.Home, "nav-home"),
    Library(
        LibraryGraph,
        LibraryDestination,
        R.string.destination_library,
        Icons.AutoMirrored.Outlined.LibraryBooks,
        "nav-library",
    ),
    Search(SearchGraph, SearchDestination, R.string.destination_search, Icons.Outlined.Search, "nav-search"),
    Downloads(
        DownloadsGraph,
        DownloadsDestination,
        R.string.destination_downloads,
        Icons.Outlined.Download,
        "nav-downloads",
    ),
    More(MoreGraph, MoreDestination, R.string.destination_more, Icons.Outlined.MoreHoriz, "nav-more"),
}
