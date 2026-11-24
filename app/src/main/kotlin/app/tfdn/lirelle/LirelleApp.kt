package app.tfdn.lirelle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.currentWindowWidthClass
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.ShownSession
import app.tfdn.lirelle.core.ui.LocalLirelleImageLoader
import app.tfdn.lirelle.navigation.AppRequests
import app.tfdn.lirelle.navigation.DownloadsRequests
import app.tfdn.lirelle.navigation.LoginNavHost
import app.tfdn.lirelle.navigation.ReaderRequests
import app.tfdn.lirelle.navigation.RootNavHost

/**
 * The app: the screen level follows the session.
 *
 * @param readerRequests the readers asked for from outside the screens, which the signed-in level opens.
 * @param downloadsRequests the Downloads destination asked for from outside the screens, which the shell selects.
 */
@Composable
fun LirelleApp(
    readerRequests: ReaderRequests,
    downloadsRequests: DownloadsRequests,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val shown by viewModel.shown.collectAsStateWithLifecycle()

    ForgetReaderRequestsWithoutProfile(shown, readerRequests)
    ForgetDownloadsRequestsWithoutProfile(shown, downloadsRequests)
    LirelleLevels(
        shown = shown,
        signIn = { startOnSignIn -> LoginNavHost(startOnSignIn = startOnSignIn) },
        signedIn = { SignedInLevel(AppRequests(readerRequests, downloadsRequests)) },
    )
}

/**
 * The signed-in app, with what its profile gives every screen: the covers' loader, the marks of the kept
 * books and whether it has a network.
 *
 * @param viewModel made here, inside the level: its store is the profile's, so it goes with it.
 */
@Composable
private fun SignedInLevel(
    requests: AppRequests,
    viewModel: SignedInViewModel = hiltViewModel(),
) {
    CompositionLocalProvider(LocalLirelleImageLoader provides viewModel.covers) {
        RootNavHost(
            appVersion = BuildConfig.VERSION_NAME,
            requests = requests,
            online = viewModel.online,
            startOffline = viewModel.deviceOffline,
            marks = viewModel.marks,
            signInNeeded = viewModel.signInNeeded,
        )
    }
}

/**
 * A reader asked for is one of the signed-in account's books: signed out, the request is dropped, and one that
 * comes then too, so that it never opens in another account or on another server. Likewise while the profile
 * is being changed: no profile is shown then, and a request is never kept from one profile for the next,
 * whichever of the two it was meant for. Otherwise the session is neither signed in nor out only at a start
 * whose update could not finish: the request is kept, and nothing opens it.
 */
@Composable
internal fun ForgetReaderRequestsWithoutProfile(shown: ShownSession, readerRequests: ReaderRequests) {
    val left = shown.accountLeft()
    val pending = readerRequests.pending
    LaunchedEffect(left, pending) { if (left) readerRequests.take() }
}

/** Nobody is signed in, or the profile is being changed: no profile is shown, and what was asked is void. */
private fun ShownSession.accountLeft() = state is SessionState.SignedOut || switching

/**
 * The downloads asked for are the signed-in account's: signed out, or while the profile is being changed, the
 * request is dropped, and one that comes then too, as a reader asked for is; likewise kept, and opened by
 * nothing, at a start whose update could not finish.
 */
@Composable
internal fun ForgetDownloadsRequestsWithoutProfile(shown: ShownSession, downloadsRequests: DownloadsRequests) {
    val left = shown.accountLeft()
    val pending = downloadsRequests.pending
    LaunchedEffect(left, pending) { if (left) downloadsRequests.take() }
}

/**
 * Picks the screen level from the session: loading, sign-in, or the signed-in app.
 *
 * @param shown the session and its profile. The signed-in level is tied to the profile's id: another profile
 *   is another level, with its own view models, so that none outlives the profile whose objects it holds.
 *   When the update of an older installation could not finish at this start, nothing is open, and its
 *   message is shown in place of every level. While the profile is being changed, a wait that says so is.
 * @param signIn the sign-in level, told whether a server is already remembered.
 * @param signedIn the signed-in level. Both are slots so this does not depend on how the
 *   screens get their data.
 */
@Composable
fun LirelleLevels(
    shown: ShownSession,
    signIn: @Composable (startOnSignIn: Boolean) -> Unit,
    signedIn: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWindowWidthClass provides currentWindowWidthClass()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                shown.updateUnfinished -> UpdateUnfinished()
                shown.switching -> ChangingProfile()
                else -> SessionLevel(shown.state, shown.profileId, signIn, signedIn)
            }
        }
    }
}

@Composable
private fun SessionLevel(
    sessionState: SessionState,
    profileId: String?,
    signIn: @Composable (startOnSignIn: Boolean) -> Unit,
    signedIn: @Composable () -> Unit,
) {
    when (sessionState) {
        SessionState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SessionState.SignedOut -> {
            // Decided once per visit to the sign-in level: remembering a server while
            // on the Server screen must not rebuild the navigation under the user.
            val startOnSignIn = remember { sessionState.serverUrl != null }
            Level { signIn(startOnSignIn) }
        }
        is SessionState.SignedIn -> key(profileId) { Level { signedIn() } }
    }
}

/**
 * All there is to see when the update could not finish: what happened and what to do, in plain text, with no
 * button — nothing the app can do at this start would help, the next start tries again. Scrolls, so that the
 * largest text size on the smallest screen still shows it whole.
 */
@Composable
private fun UpdateUnfinished() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(AloneOnScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.update_unfinished),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * All there is to see while the app goes from one profile to another: a wait, and what is waited for, so that
 * it is not taken for the app starting again or for a sign-out. The text is a polite live region: a screen
 * reader says it when it appears, without cutting what it was saying. Scrolls, like the message above.
 */
@Composable
private fun ChangingProfile() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(AloneOnScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ChangingProfileSpacing, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.profile_changing),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private val AloneOnScreenPadding = 24.dp
private val ChangingProfileSpacing = 16.dp

/**
 * Gives a screen level its own view model store. The store survives a configuration change
 * and is cleared when the level leaves the screen, so its view models do not outlive it.
 */
@Composable
private fun Level(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides rememberViewModelStoreOwner(), content = content)
}
