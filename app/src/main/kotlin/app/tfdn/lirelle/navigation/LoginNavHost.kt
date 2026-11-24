package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.tfdn.lirelle.feature.login.ProfileLabel
import app.tfdn.lirelle.feature.login.ProviderPageRoute
import app.tfdn.lirelle.feature.login.ServerRoute
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.feature.login.SignInRoute

/**
 * The sign-in steps: Server, then Sign-in, over which a provider's page may open in a WebView of the app. They
 * are the whole sign-in level while nobody is signed in, and are
 * shown over the current profile, above the shell, to add a profile or to sign in again to one.
 *
 * @param startOnSignIn true when the server is not asked first: one is already remembered at a first sign-in,
 *   or a profile signs in again on its own.
 * @param purpose what the sign-in is for.
 * @param again the profile that signs in again, when that is the purpose.
 * @param onSignedIn told once a sign-in has succeeded and its session is saved.
 * @param onLeave returns to the profile the steps are shown over: asked when the profile that was to sign in
 *   again is no longer there. Never asked at a first sign-in.
 */
@Composable
fun LoginNavHost(
    startOnSignIn: Boolean,
    purpose: SignInPurpose = SignInPurpose.First,
    again: ProfileLabel? = null,
    onSignedIn: () -> Unit = {},
    onLeave: () -> Unit = {},
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (startOnSignIn) SignInDestination else ServerDestination,
    ) {
        composable<ServerDestination> {
            ServerRoute(
                onConnected = { navController.navigate(SignInDestination) { launchSingleTop = true } },
                purpose = purpose,
            )
        }
        composable<SignInDestination> {
            val toServer = {
                navController.navigate(ServerDestination) {
                    popUpTo<SignInDestination> { inclusive = true }
                    launchSingleTop = true
                }
            }
            SignInRoute(
                // A profile signs in again on its own server: without it there is nothing to sign in to.
                onChangeServer = if (purpose is SignInPurpose.Again) onLeave else toServer,
                purpose = purpose,
                again = again,
                onSignedIn = onSignedIn,
                onProviderPage = { navController.navigate(ProviderPageDestination) { launchSingleTop = true } },
            )
        }
        composable<ProviderPageDestination> { entry ->
            // The sign-in step under it holds the page and finishes the sign-in once the page has ended.
            val signInStep = remember(entry) { navController.getBackStackEntry<SignInDestination>() }
            ProviderPageRoute(
                signInStep = signInStep,
                onClose = { navController.popBackStack<ProviderPageDestination>(inclusive = true) },
                purpose = purpose,
                again = again,
            )
        }
    }
}
