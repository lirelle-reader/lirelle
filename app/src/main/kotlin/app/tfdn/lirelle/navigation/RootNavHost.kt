package app.tfdn.lirelle.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tfdn.lirelle.ListenViewModel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.ui.LocalSignInAgain
import app.tfdn.lirelle.core.ui.LocalSignInNeeded
import app.tfdn.lirelle.core.ui.SignInNeeded
import app.tfdn.lirelle.feature.account.ProfilesRoute
import app.tfdn.lirelle.feature.login.RedirectsOutsideSignIn
import app.tfdn.lirelle.feature.login.SignInOverProfile
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.feature.player.MiniPlayer
import app.tfdn.lirelle.feature.player.PlayerScreen
import app.tfdn.lirelle.feature.player.PlayerSettingsRoute
import app.tfdn.lirelle.feature.player.PlayerViewModel
import app.tfdn.lirelle.feature.reader.EndPanelSheet
import app.tfdn.lirelle.feature.reader.ReaderScreen
import app.tfdn.lirelle.feature.reader.ReaderSheets
import app.tfdn.lirelle.feature.reader.ReaderViewModel
import app.tfdn.lirelle.profile.CurrentProfileAvatar
import app.tfdn.lirelle.shell.MoreViewModel
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.shell.TopLevelDestination
import app.tfdn.lirelle.shell.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in level, with the real screens.
 *
 * @param requests what is asked for from outside the screens.
 * @param online whether the profile has a network: when the shell's screens try again.
 * @param startOffline whether the device has none at this moment: where the shell starts.
 * @param marks the mark of each book the device keeps something of, which the covers of the shell carry.
 * @param signInNeeded the profile while it waits for a sign-in: what the shell's screens say when they cannot
 *   reach its server.
 */
@Composable
fun RootNavHost(
    appVersion: String,
    requests: AppRequests,
    online: StateFlow<Boolean>,
    startOffline: Boolean,
    marks: StateFlow<Map<BookId, DownloadMark>>,
    signInNeeded: StateFlow<SignInNeeded?>,
    listening: ListenViewModel = hiltViewModel(),
) {
    RootNavHost(
        // The profile button and the Profiles page open the sign-in screens to add a profile; the page, and
        // Home's notice of an expired session, for one to sign in again.
        shell = { modes, bookRequests, player, profiles ->
            val snackbars = remember { SnackbarHostState() }
            Shell(
                account = { onBack -> ProfilesRoute(onBack, profiles.addProfile, profiles.signInAgain) },
                home = { shellController ->
                    homeDestinations(
                        navController = shellController,
                        modes = modes,
                        onSignInAgain = profiles.signInAgain,
                        // Where the screen shown for lack of network leads: the shell's Downloads destination.
                        onOpenDownloads = { shellController.select(TopLevelDestination.Downloads) },
                    ) { TitleProfileButton(profiles, shellController) }
                },
                library = { libraryDestinations(it, modes) { TitleProfileButton(profiles, it) } },
                search = { searchDestinations(it, modes) },
                downloads = { downloadsDestinations(it, modes) },
                more = { moreDestinations(it, modes, appVersion) },
                downloadsRequests = requests.downloads,
                online = online,
                startOffline = startOffline,
                marks = marks,
                bookRequests = bookRequests,
                miniPlayer = { modifier ->
                    MiniPlayer(hiltViewModel(), player.onOpenPlayer, player.onOpenReader, snackbars, modifier)
                },
                snackbars = snackbars,
                railProfiles = { onManageProfiles -> RailProfileButton(profiles, onManageProfiles) },
                historyOffered = { hiltViewModel<MoreViewModel>().history },
            )
        },
        reader = { book, onLeave, onOpenBook -> ReaderRoute(book, onLeave, onOpenBook) },
        comics = { album, onLeave, onOpenNext -> ComicsRoute(album, onLeave, onOpenNext) },
        player = { onMinimise, onOpenBook, onOpenSettings -> PlayerRoute(onMinimise, onOpenBook, onOpenSettings) },
        playerSettings = { onBack -> PlayerSettingsRoute(onBack) },
        listen = listening::listen,
        readerRequests = requests.reader,
        downloadsRequests = requests.downloads,
        signIn = { purpose, onLeave ->
            SignInOverProfile(purpose, onLeave, avatar = { CurrentProfileAvatar() }) { again, onSignedIn ->
                // A profile signing in again does so on its own server: the server is not asked.
                LoginNavHost(purpose is SignInPurpose.Again, purpose, again, onSignedIn, onLeave)
            }
        },
        outsideSignIn = { signInShown -> RedirectsOutsideSignIn(signInShown) },
        signInNeeded = signInNeeded,
    )
}

/**
 * Signed-in level. The shell is one destination; the reader, the audio player, the player
 * settings and the sign-in screens shown over the profile are others beside it, so they are shown
 * without the navigation bar or rail, whichever tab they were opened from. Leaving one returns to
 * where it was opened from, as it was.
 *
 * @param shell the shell, given what opens the reader or the player on a book, the requests it is to
 *   answer by opening a book's page in the tab in front, the ways from its mini-player into the
 *   player and the reader, and the ways into the sign-in screens over the profile ([ProfileEntrances]:
 *   `addProfile()`, `signInAgain(profileId)`). A slot, as the others are, so that this does not depend
 *   on how the screens get their data.
 * @param reader the reader of a book, given the book and where to open it, what leaves it, and
 *   what leaves it for the page of another book.
 * @param player the player of the book loaded, given what minimises it (or leaves it once its book
 *   is closed), what leaves it for the page of a book, and what opens its settings.
 * @param playerSettings the player settings, given what goes back.
 * @param listen loads a book in the player and plays it, from its start when it is listened to again.
 * @param readerRequests the readers asked for from outside the screens (a narration's notification): each opens
 *   once, as the mini-player's does.
 * @param downloadsRequests the Downloads destination asked for from outside the screens: what is above the
 *   shell gives way, and the shell, which is given the same requests, selects the destination.
 * @param comics the comics reader of an album, given the album and where to open it, what leaves it, and what
 *   puts the reader of another album in its place.
 * @param signIn the sign-in screens shown over the profile, given what they are shown for — a profile added,
 *   or one signing in again — and what returns to the profile.
 * @param outsideSignIn placed beside the screens and told whether the sign-in screens are shown over the
 *   profile: what is to be done when they are not, and when they are left.
 * @param signInNeeded the current profile while it waits for a sign-in, null while it does not: the shell's
 *   screens are told ([ProvideSignInNeeded]), with the way to sign it in again. Null when that is not known.
 */
@Composable
fun RootNavHost(
    shell: @Composable (
        modes: BookModes,
        bookRequests: BookRequests,
        player: PlayerEntrances,
        profiles: ProfileEntrances,
    ) -> Unit,
    reader: @Composable (book: ReaderDestination, onLeave: () -> Unit, onOpenBook: (BookId) -> Unit) -> Unit,
    player: @Composable (onMinimise: () -> Unit, onOpenBook: (BookId) -> Unit, onOpenSettings: () -> Unit) -> Unit,
    playerSettings: @Composable (onBack: () -> Unit) -> Unit,
    listen: (bookId: BookId, fromStart: Boolean) -> Unit,
    readerRequests: ReaderRequests = remember { ReaderRequests() },
    downloadsRequests: DownloadsRequests = remember { DownloadsRequests() },
    comics: @Composable (album: ComicsDestination, onLeave: () -> Unit, onOpenNext: (BookId) -> Unit) -> Unit =
        { _, _, _ -> },
    signIn: @Composable (purpose: SignInPurpose, onLeave: () -> Unit) -> Unit = { _, _ -> },
    outsideSignIn: @Composable (signInShown: Boolean) -> Unit = {},
    signInNeeded: StateFlow<SignInNeeded?>? = null,
) {
    val navController = rememberNavController()
    val bookRequests = remember { BookRequests() }
    NavHost(navController = navController, startDestination = ShellDestination) {
        composable<ShellDestination> { entry ->
            // The shell's screens have the same guard on their own entries; this one also holds
            // when two of them ask at once.
            // The book is loaded, then the player opened on it; once only, for a double tap.
            val onListen = entry.ifResumedWith<Pair<BookId, Boolean>> { (bookId, fromStart) ->
                listen(bookId, fromStart)
                navController.navigate(PlayerDestination)
            }
            val modes = BookModes(
                onRead = entry.ifResumedWith { navController.navigate(it) },
                onListen = { bookId, fromStart -> onListen(bookId to fromStart) },
                onReadComic = entry.ifResumedWith { navController.navigate(it) },
            )
            val entrances = PlayerEntrances(
                onOpenPlayer = entry.ifResumed { navController.navigate(PlayerDestination) },
                // A narration is heard in the reader, which shows where it is.
                onOpenReader = entry.ifResumedWith(navController::openReader),
            )
            val profiles = navController.profileEntrances(entry)
            ProvideSignInNeeded(signInNeeded, profiles.signInAgain) {
                shell(modes, bookRequests, entrances, profiles)
            }
        }
        composable<ReaderDestination> { entry ->
            // Before the screen's own: registered after the shell's, so that a Back pressed while the
            // screen is still coming in leaves it, rather than going back in the tab underneath.
            BackHandler { navController.leave<ReaderDestination>() }
            reader(
                entry.toRoute<ReaderDestination>(),
                { navController.leave<ReaderDestination>() },
                { bookId -> if (navController.leave<ReaderDestination>()) bookRequests.open(bookId) },
            )
        }
        comicsDestination(navController, comics)
        composable<PlayerDestination> { entry ->
            BackHandler { navController.leave<PlayerDestination>() }
            player(
                { navController.leave<PlayerDestination>() },
                { bookId -> if (navController.leave<PlayerDestination>()) bookRequests.open(bookId) },
                entry.ifResumed { navController.navigate(PlayerSettingsDestination) },
            )
        }
        composable<PlayerSettingsDestination> {
            BackHandler { navController.leave<PlayerSettingsDestination>() }
            playerSettings { navController.leave<PlayerSettingsDestination>() }
        }
        signInOverProfileDestination(navController, signIn)
    }
    outsideSignIn(navController.signInOverProfileShown())
    // After the graph is set: a request made before the screens existed waits for it.
    val requested = readerRequests.pending
    LaunchedEffect(requested) { readerRequests.take()?.let(navController::openReader) }
    // The shell takes the request once it is in front again.
    val downloadsAsked = downloadsRequests.pending
    LaunchedEffect(downloadsAsked) {
        if (downloadsAsked) navController.popBackStack<ShellDestination>(inclusive = false)
    }
}

private val NoSignInNeeded: StateFlow<SignInNeeded?> = MutableStateFlow(null)

/**
 * Tells every screen of the shell whether the current profile waits for a sign-in ([LocalSignInNeeded]) and
 * how to sign it in again ([LocalSignInAgain]): a screen that cannot reach its server then says so and offers
 * it, with no parameter of its own, and no feature knows of another for it.
 *
 * @param needed the profile while it waits for a sign-in, null while it does not; null when not known.
 */
@Composable
private fun ProvideSignInNeeded(
    needed: StateFlow<SignInNeeded?>?,
    signInAgain: (profileId: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val current by (needed ?: NoSignInNeeded).collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalSignInNeeded provides current,
        LocalSignInAgain provides signInAgain,
        content = content,
    )
}

/**
 * The reader of [bookId], where its narration is: nothing when it is the screen in front already, which goes
 * on as it is (narrating or not). Otherwise what is above the shell (the reader of another book, the player,
 * its settings) gives way to it, so that Back returns to the shell.
 */
private fun NavHostController.openReader(bookId: BookId) {
    val front = currentBackStackEntry?.takeIf { it.destination.hasRoute<ReaderDestination>() }
    if (front?.toRoute<ReaderDestination>()?.bookId == bookId.value) return
    navigate(ReaderDestination(bookId.value)) { popUpTo<ShellDestination>() }
}

/**
 * Back to what is under the screen [T], once: it may ask again while it is leaving, even before it
 * is resumed, and what is under it must stay. False when it was already left.
 */
private inline fun <reified T : Any> NavHostController.leave(): Boolean =
    currentDestination?.hasRoute<T>() == true && popBackStack()

/**
 * True where a screen is shown for the first time, false once it is made again from its saved
 * state: after the system stopped the app behind the user's back, but also after a rotation, or
 * when the screen comes back from one opened over it.
 */
@Composable
internal fun rememberIsFirstShowing(): Boolean =
    rememberSaveable(saver = Saver<Boolean, Boolean>(save = { false }, restore = { it })) { true }

/**
 * The player, which leaves as minimising does once its book is closed from the end panel. Made again
 * from its saved state, it leaves by itself once the session shows that nothing is loaded: meant for the
 * system having ended the app, and the book with it, but a rotation or a return from the player
 * settings is made again from its saved state too. Those keep the player while its book is loaded, and
 * its view model, which they keep, waits only once.
 */
@Composable
private fun PlayerRoute(onMinimise: () -> Unit, onOpenBook: (BookId) -> Unit, onOpenSettings: () -> Unit) {
    val viewModel = hiltViewModel<PlayerViewModel>()
    val restored = !rememberIsFirstShowing()
    LaunchedEffect(viewModel) { if (restored) viewModel.leaveIfNothingLoaded() }
    PlayerScreen(viewModel, onMinimise, onOpenBook, onOpenSettings)
}

/**
 * The reader with its sheets and its end panel, and a view model of its own for each book and
 * each way to open it.
 */
@Composable
private fun ReaderRoute(book: ReaderDestination, onLeave: () -> Unit, onOpenBook: (BookId) -> Unit) {
    // A book read again starts over once, and a narration asked for starts once: a reader made again later
    // goes on where the user got to.
    val first = rememberIsFirstShowing()
    ReaderScreen(
        viewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory>(
            key = "reader-${book.bookId}-${book.fromStart}-${book.narrate}",
            creationCallback = { it.create(book.bookId, book.fromStart && first, book.narrate && first) },
        ),
        onLeave = onLeave,
        onOpenBook = onOpenBook,
        sheets = { state, actions -> ReaderSheets(state, actions) },
        endPanel = { state, actions ->
            state.endPanel?.let { EndPanelSheet(it, state.title, state.colours, actions) }
        },
    )
}
