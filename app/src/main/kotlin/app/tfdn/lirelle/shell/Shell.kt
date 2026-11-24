package app.tfdn.lirelle.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.ui.LocalDeviceOnline
import app.tfdn.lirelle.core.ui.LocalDownloadMarks
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.navigation.AboutDestination
import app.tfdn.lirelle.navigation.AccountDestination
import app.tfdn.lirelle.navigation.BookDestination
import app.tfdn.lirelle.navigation.HistoryDestination
import app.tfdn.lirelle.navigation.BookRequests
import app.tfdn.lirelle.navigation.DownloadsDestination
import app.tfdn.lirelle.navigation.DownloadsGraph
import app.tfdn.lirelle.navigation.DownloadsRequests
import app.tfdn.lirelle.navigation.HomeDestination
import app.tfdn.lirelle.navigation.HomeGraph
import app.tfdn.lirelle.navigation.LibraryDestination
import app.tfdn.lirelle.navigation.LibraryGraph
import app.tfdn.lirelle.navigation.MoreDestination
import app.tfdn.lirelle.navigation.MoreGraph
import app.tfdn.lirelle.navigation.SearchDestination
import app.tfdn.lirelle.navigation.SearchGraph
import app.tfdn.lirelle.navigation.SettingsDestination
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in app: five destinations behind a bottom bar (compact width) or a navigation
 * rail (medium and expanded width).
 *
 * @param account the Profiles page (More › Profiles), given a callback that goes back. It is a slot so the
 *   shell does not depend on how that screen gets its data.
 * @param home the destinations of the Home graph; its start destination is [HomeDestination].
 *   A slot for the same reason as [account].
 * @param library the destinations of the Library graph; its start destination is
 *   [LibraryDestination].
 * @param search the destinations of the Search graph; its start destination is
 *   [SearchDestination].
 * @param downloads the destinations of the Downloads graph; its start destination is
 *   [DownloadsDestination]. An empty screen unless given.
 * @param downloadsRequests the Downloads destination asked for from outside the screens (the notification of
 *   a download that failed): it is selected, once.
 * @param online whether the profile has a network — the device has one, and the profile's session does not
 *   wait for a sign-in; null when that is not known. The screens are given it ([LocalDeviceOnline]) with the
 *   way to the downloaded books ([LocalOpenDownloads]): what failed to load for lack of network leads there,
 *   and is tried again when the network is back.
 * @param startOffline whether the device has no network when the shell is first shown: it then starts on
 *   Downloads instead of Home. Only then, not when it is made again from its saved state nor when the network
 *   is lost later. The device's network, not the profile's: a profile that waits for a sign-in while the
 *   device has a network starts on Home, where that is said.
 * @param marks the mark of each book the device keeps something of; null when that is not known. The screens
 *   are given it ([LocalDownloadMarks]): their covers carry the mark of their book.
 * @param bookRequests books asked for from above the shell: the page of each is opened in the
 *   tab in front, on top of what that tab shows.
 * @param miniPlayer the mini-player, given the modifier that keeps it clear of the system bars:
 *   above the bottom bar, or at the bottom of the content area beside the rail. The content ends
 *   above it. It shows nothing while no book is loaded, and the content then goes down to the bar.
 * @param more the destinations opened from More, beside the profiles: the reading history and what it leads to,
 *   the settings ([SettingsDestination]) and About ([AboutDestination]). A slot for the same reason as
 *   [account]. Empty unless given: the rows of More that open them need it.
 * @param snackbars the shell's messages ("Listening stopped"), shown over the bottom of the screens:
 *   above the mini-player and the bottom bar or beside the rail, never over them.
 * @param railProfiles the profile button where the destinations are in a rail: at its bottom, under them.
 *   Given what opens the page where the profiles are managed. With the bottom bar it is not shown: the
 *   button is then in the title of Home and of Library, which [home] and [library] see to.
 * @param historyOffered whether More offers the reading history: false for a source that keeps none (spec 2c).
 *   Asked inside More's destination, so that what it reads lives with that destination; a slot for the same
 *   reason as [account].
 */
@Composable
fun Shell(
    account: @Composable (onBack: () -> Unit) -> Unit,
    home: NavGraphBuilder.(NavHostController) -> Unit,
    library: NavGraphBuilder.(NavHostController) -> Unit,
    search: NavGraphBuilder.(NavHostController) -> Unit,
    modifier: Modifier = Modifier,
    downloads: NavGraphBuilder.(NavHostController) -> Unit = { composable<DownloadsDestination> {} },
    more: NavGraphBuilder.(NavHostController) -> Unit = {},
    downloadsRequests: DownloadsRequests = remember { DownloadsRequests() },
    online: StateFlow<Boolean>? = null,
    startOffline: Boolean = false,
    marks: StateFlow<Map<BookId, DownloadMark>>? = null,
    bookRequests: BookRequests = remember { BookRequests() },
    miniPlayer: @Composable (Modifier) -> Unit = {},
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    railProfiles: @Composable (onManageProfiles: () -> Unit) -> Unit = {},
    historyOffered: @Composable () -> Boolean = { true },
) {
    val navController = rememberNavController()
    // Decided once, and kept with the navigation's own state: the graph made again starts where this one did.
    val startedOffline = rememberSaveable { startOffline }
    val graphs = ShellGraphs(
        home, library, search, downloads, more, historyOffered,
        if (startedOffline) DownloadsGraph else HomeGraph,
    )
    LaunchedEffect(downloadsRequests.pending) {
        if (downloadsRequests.take()) navController.select(TopLevelDestination.Downloads)
    }
    val openDownloads = remember(navController) { { navController.select(TopLevelDestination.Downloads) } }
    // After the composition that gave the controller its graph: the tab in front is known.
    LaunchedEffect(bookRequests.pending) {
        // The page of the book already in front stays as it is: no second copy over it.
        bookRequests.take()?.let { id ->
            if (!navController.showsBook(id)) navController.navigate(BookDestination(id.value))
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val layoutType = when (LocalWindowWidthClass.current) {
        WindowWidthClass.Compact -> NavigationSuiteType.NavigationBar
        WindowWidthClass.Medium, WindowWidthClass.Expanded -> NavigationSuiteType.NavigationRail
    }

    ShellScaffold(
        layoutType = layoutType,
        destinations = ShellDestinations(isSelected = currentDestination::isIn, onSelect = navController::select),
        modifier = modifier,
        railFooter = { railProfiles(navController::openProfilesPage) },
    ) {
        Column(Modifier.fillMaxSize()) {
            ContentAboveNavigation(Modifier.weight(1f)) {
                CompositionLocalProvider(
                    LocalOpenDownloads provides openDownloads,
                    LocalDeviceOnline provides online,
                    LocalDownloadMarks provides marks,
                ) {
                    ShellNavHost(navController, graphs, account)
                }
                SnackbarHost(
                    hostState = snackbars,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(SnackbarInsets)
                        .testTag(ShellSnackbarsTag),
                )
            }
            ContentAboveNavigation(Modifier.fillMaxWidth()) {
                miniPlayer(Modifier.windowInsetsPadding(MiniPlayerInsets))
            }
        }
    }
}

/**
 * What the mini-player keeps clear of: the system bars and a display cutout, under it and at its
 * sides; not the keyboard, which covers it as it covers the bottom bar.
 */
private val MiniPlayerInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

internal const val ShellSnackbarsTag = "shell-snackbars"

/**
 * What a snackbar keeps clear of: the system bars, a display cutout and the keyboard, under it and at
 * its sides. The bar or the mini-player under it are taken off already.
 */
private val SnackbarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * The graphs the shell is given, and the one it starts on.
 *
 * @property start [HomeGraph], or [DownloadsGraph] for a shell first shown without a network.
 */
private class ShellGraphs(
    val home: NavGraphBuilder.(NavHostController) -> Unit,
    val library: NavGraphBuilder.(NavHostController) -> Unit,
    val search: NavGraphBuilder.(NavHostController) -> Unit,
    val downloads: NavGraphBuilder.(NavHostController) -> Unit,
    val more: NavGraphBuilder.(NavHostController) -> Unit,
    val historyOffered: @Composable () -> Boolean,
    val start: Any,
)

/** The five destinations, each a graph with its own back stack. */
@Composable
private fun ShellNavHost(
    navController: NavHostController,
    graphs: ShellGraphs,
    account: @Composable (onBack: () -> Unit) -> Unit,
) {
    NavHost(navController = navController, startDestination = graphs.start) {
        navigation<HomeGraph>(startDestination = HomeDestination) {
            graphs.home(this, navController)
        }
        navigation<LibraryGraph>(startDestination = LibraryDestination) {
            graphs.library(this, navController)
        }
        navigation<DownloadsGraph>(startDestination = DownloadsDestination) {
            graphs.downloads(this, navController)
        }
        navigation<MoreGraph>(startDestination = MoreDestination) {
            composable<MoreDestination> {
                // Dropped unless this screen is the one in front: the second tap of a
                // double tap must not open the Profiles page twice.
                val history = graphs.historyOffered()
                val openHistory = dropUnlessResumed { navController.navigate(HistoryDestination) }
                MoreScreen(
                    onOpenProfiles = dropUnlessResumed { navController.navigate(AccountDestination) },
                    onOpenHistory = openHistory.takeIf { history },
                    onOpenSettings = dropUnlessResumed { navController.navigate(SettingsDestination) },
                    onOpenAbout = dropUnlessResumed { navController.navigate(AboutDestination) },
                )
            }
            composable<AccountDestination> {
                // Same guard: a second tap on back must not also leave More.
                account(dropUnlessResumed { navController.popBackStack() })
            }
            graphs.more(this, navController)
        }
        navigation<SearchGraph>(startDestination = SearchDestination) {
            graphs.search(this, navController)
        }
    }
}

/**
 * A part of the content area of the shell: the screens, or the mini-player under them. Window
 * insets are measured from the edges of the window, but this part ends above the bottom bar (and
 * the screens above the mini-player): it takes what lies under it off the insets its content
 * sees. A screen that pads itself for the keyboard then stops at the keyboard, instead of
 * leaving the height of the bar empty above it.
 */
@Composable
private fun ContentAboveNavigation(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    var below by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val root = coordinates.findRootCoordinates()
                val bottom = root.localBoundingBoxOf(coordinates, clipBounds = false).bottom
                below = (root.size.height - bottom).roundToInt().coerceAtLeast(0)
            }
            .consumeWindowInsets(WindowInsets(bottom = below)),
    ) { content() }
}
