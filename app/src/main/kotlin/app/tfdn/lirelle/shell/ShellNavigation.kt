package app.tfdn.lirelle.shell

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.navigation.AccountDestination
import app.tfdn.lirelle.navigation.BookDestination
import app.tfdn.lirelle.navigation.MoreDestination

/**
 * Selects a top-level destination, as a tap on its item of the bar or rail does: the one in front goes back to
 * its first screen, another comes in front where it was left.
 */
internal fun NavHostController.select(destination: TopLevelDestination) {
    if (currentDestination.isIn(destination)) popToRootOf(destination) else switchTo(destination)
}

/**
 * Opens the page where the profiles are managed — More › Profiles —, from anywhere in the shell: More comes in
 * front, on its first screen, and the page over it, so that Back leads to More whatever More showed before.
 * Nothing moves when the page is in front already.
 */
internal fun NavHostController.openProfilesPage() {
    if (currentDestination?.hasRoute<AccountDestination>() == true) return
    if (!currentDestination.isIn(TopLevelDestination.More)) switchTo(TopLevelDestination.More)
    popBackStack(MoreDestination, inclusive = false)
    navigate(AccountDestination) { launchSingleTop = true }
}

/** Whether the screen in front is the page of the book [id]. */
internal fun NavHostController.showsBook(id: BookId): Boolean {
    val entry = currentBackStackEntry ?: return false
    return entry.destination.hasRoute<BookDestination>() && entry.toRoute<BookDestination>().id == id.value
}

internal fun NavDestination?.isIn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.graph::class) } == true

/** Switches top-level destination, saving the one left and restoring the one entered. */
private fun NavHostController.switchTo(destination: TopLevelDestination) {
    navigate(destination.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Brings the selected top-level destination back to its first screen. */
private fun NavHostController.popToRootOf(destination: TopLevelDestination) {
    popBackStack(destination.root, inclusive = false)
}
