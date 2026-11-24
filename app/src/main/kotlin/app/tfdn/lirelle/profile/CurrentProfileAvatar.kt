package app.tfdn.lirelle.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarModel
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.core.ui.avatarModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** How long the avatar is still followed once nothing shows it: a rotation does not start it over. */
private const val KeepFollowingMillis = 5_000L

/**
 * The avatar of the profile that is [current], with the picture the device keeps of its account
 * ([avatars]), as either changes; null while nobody is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun currentAvatar(current: Flow<Profile?>, avatars: ProfileAvatars): Flow<ProfileAvatarModel?> =
    current.flatMapLatest { profile ->
        if (profile == null) flowOf(null) else avatars.picture(profile.id).map { profile.avatarModel(it) }
    }

/**
 * The avatar of the current profile, for a screen shown over it — the sign-in screens' bar. It reads the
 * registry and the device's pictures only, nothing of the profile's own objects: it may live while the
 * profile changes under it.
 */
@HiltViewModel
class CurrentProfileAvatarViewModel @Inject constructor(store: ProfileStore, avatars: ProfileAvatars) : ViewModel() {

    /** Starts on the initial, at once: the picture is read from the disk, and comes after. */
    val avatar: StateFlow<ProfileAvatarModel?> = currentAvatar(store.current, avatars).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(KeepFollowingMillis),
        store.current.value?.avatarModel(picture = null),
    )
}

/**
 * The current profile's avatar at its smallest size, the one that goes beside a line of text — the bar of the
 * sign-in screens; nothing while nobody is current. Decoration: whoever shows it names the profile beside it.
 */
@Composable
internal fun CurrentProfileAvatar(
    modifier: Modifier = Modifier,
    viewModel: CurrentProfileAvatarViewModel = hiltViewModel(),
) {
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    avatar?.let { ProfileAvatar(it, ProfileAvatarSize.Small, modifier) }
}
