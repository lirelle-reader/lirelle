package app.tfdn.lirelle.navigation

import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.account.switcher.ProfileSwitcher
import app.tfdn.lirelle.feature.account.switcher.ProfilesHost
import app.tfdn.lirelle.feature.account.switcher.ProfilesSwitcherViewModel
import app.tfdn.lirelle.shell.openProfilesPage

/**
 * From the rail's button to the menu: the button is centred in a rail 80 dp wide, 16 dp from its edge, and
 * the menu stands 8 dp clear of the rail.
 */
private val RailMenuGap = 24.dp

/**
 * The profile button at the end of the title of Home or of the Library, where the destinations are in a
 * bottom bar; its profiles open in a sheet. Nothing on wider screens: the button is in the rail there
 * ([RailProfileButton]), and one is enough.
 *
 * @param entrances the ways into the sign-in screens: "Add a profile" takes the first.
 * @param navController the shell's, which opens the page where the profiles are managed.
 * @param viewModel gives the profiles of the device, kept by the screen whose title this is in; asked only
 *   where the button is shown.
 */
@Composable
internal fun TitleProfileButton(
    entrances: ProfileEntrances,
    navController: NavHostController,
    viewModel: @Composable () -> ProfilesSwitcherViewModel = { hiltViewModel() },
) {
    if (LocalWindowWidthClass.current != WindowWidthClass.Compact) return
    ProfileSwitcher(
        host = ProfilesHost.Sheet,
        onAddProfile = entrances.addProfile,
        onManageProfiles = navController::openProfilesPage,
        viewModel = viewModel(),
    )
}

/**
 * The profile button at the bottom of the rail; its profiles open in a menu beside it, clear of the rail.
 *
 * @param onManageProfiles opens the page where the profiles are managed: the shell knows how.
 * @param viewModel the profiles of the device; kept by the shell's own screen.
 */
@Composable
internal fun RailProfileButton(
    entrances: ProfileEntrances,
    onManageProfiles: () -> Unit,
    viewModel: ProfilesSwitcherViewModel = hiltViewModel(),
) {
    ProfileSwitcher(
        host = ProfilesHost.Menu(RailMenuGap),
        onAddProfile = entrances.addProfile,
        onManageProfiles = onManageProfiles,
        // The rail's own ground, not the screens'.
        ringColor = NavigationRailDefaults.ContainerColor,
        viewModel = viewModel,
    )
}
