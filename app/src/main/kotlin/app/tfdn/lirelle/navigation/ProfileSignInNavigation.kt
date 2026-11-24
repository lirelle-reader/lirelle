package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.feature.login.SignInPurpose

/** What the sign-in screens of this destination are shown for. */
internal val SignInOverProfileDestination.purpose: SignInPurpose
    get() = profileId?.let(SignInPurpose::Again) ?: SignInPurpose.Add

/** The ways into the sign-in screens over the profile, from the shell while it is the screen in front. */
internal fun NavHostController.profileEntrances(shell: NavBackStackEntry) = ProfileEntrances(
    addProfile = shell.ifResumed { navigate(SignInOverProfileDestination()) },
    signInAgain = shell.ifResumedWith { profileId -> navigate(SignInOverProfileDestination(profileId)) },
)

/**
 * The sign-in screens over the current profile, beside the shell as the readers are: shown without the
 * navigation bar or rail, and left for where they were opened from, as it was.
 *
 * @param signIn the sign-in screens, given what they are shown for and what returns to the profile.
 */
internal fun NavGraphBuilder.signInOverProfileDestination(
    navController: NavHostController,
    signIn: @Composable (purpose: SignInPurpose, onLeave: () -> Unit) -> Unit,
) {
    composable<SignInOverProfileDestination> { entry ->
        // Once: asked again while leaving, or once the level itself is leaving for another profile, what is
        // under must stay.
        val leave = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() }
        // No Back handler here: the screens have their own, which is the one that ends a sign-in in flight.
        signIn(entry.toRoute<SignInOverProfileDestination>().purpose) { leave() }
    }
}

/**
 * Whether the sign-in screens over the profile are the screen in front. Nothing is ever opened above them.
 *
 * Right from the first composition, which is why it must be asked after the graph is set: a level made again
 * from its saved state has its screens back by then, and what is told here decides who takes an OIDC redirect
 * and whether a sign-in still waited for is cancelled. Saying "not shown" for one frame would hand the redirect
 * of the sign-in screen made again to what stands beside the screens, or cancel that sign-in.
 */
@Composable
internal fun NavHostController.signInOverProfileShown(): Boolean {
    val front by currentBackStackEntryFlow.collectAsState(initial = currentBackStackEntry)
    return front?.destination?.hasRoute<SignInOverProfileDestination>() == true
}
