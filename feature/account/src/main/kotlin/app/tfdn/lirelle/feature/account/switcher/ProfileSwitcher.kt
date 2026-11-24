package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The profile button with the list it opens, for the profiles of the device as they are now. Nothing at all
 * with one profile.
 *
 * @param host what holds the list: a sheet, or a menu beside the button.
 * @param onAddProfile opens the sign-in screens to add a profile.
 * @param onManageProfiles opens the page where the profiles are managed.
 * @param ringColor the ground the button lies on.
 */
@Composable
fun ProfileSwitcher(
    host: ProfilesHost,
    onAddProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.background,
    viewModel: ProfilesSwitcherViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileSwitcher(state, host, viewModel::switchTo, onAddProfile, onManageProfiles, modifier, ringColor)
}

/**
 * The profile button with the list it opens.
 *
 * Whatever is chosen in the list closes it first. Choosing the current profile does nothing more; choosing
 * another asks for the switch — also when that profile waits for a sign-in: it is opened as it is, and says
 * so itself.
 *
 * The list is open for the profile it was opened over, and for no other: once the current profile changes —
 * a switch, a profile added — it is closed, here and in what is saved of this screen. Everything shown goes
 * with the button: when the profiles are one again, or the screen is torn down under the list, the list goes
 * with it.
 *
 * @param onSwitch asks for the profile of that id to become the current one.
 */
@Composable
fun ProfileSwitcher(
    state: SwitcherState,
    host: ProfilesHost,
    onSwitch: (profileId: String) -> Unit,
    onAddProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.background,
) {
    val current = state.current
    if (!state.severalProfiles || current == null) return
    var openFor by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { openFor = null }
    // Forgotten, not only hidden: the list must not come back by itself when that profile is current again.
    SideEffect { if (openFor != null && openFor != current.id) openFor = null }
    val actions = ProfilesActions(
        onPick = { row ->
            close()
            if (!row.current) onSwitch(row.id)
        },
        onAdd = {
            close()
            onAddProfile()
        },
        onManage = {
            close()
            onManageProfiles()
        },
    )
    // The menu is anchored to this box, which is the button's size.
    Box(modifier) {
        ProfileButton(state, onClick = { openFor = current.id }, ringColor = ringColor)
        if (openFor == current.id) {
            when (host) {
                ProfilesHost.Sheet -> ProfilesSheet(state, actions, close)
                is ProfilesHost.Menu -> ProfilesMenu(state, actions, close, host.gap)
            }
        }
    }
}
