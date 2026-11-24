package app.tfdn.lirelle.feature.login

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.session.ProfileStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * What the sign-in screens shown over a profile say of the profiles.
 *
 * @property current the profile they were opened over, which the back arrow returns to; null when there was
 *   none any more — its level is then leaving.
 * @property again the profile that signs in again; null when a profile is added, and when that profile is no
 *   longer on the device.
 */
data class SignInOverProfileUiState(val current: ProfileLabel?, val again: ProfileLabel?)

/**
 * The sign-in screens shown over the current profile, to add a profile or to sign in again to one: whom they
 * name, and whether a sign-in that succeeded leaves them.
 *
 * It reads the registry, which is the device's, and nothing of the open profile's own objects: the sign-in is
 * about no profile, and the current one may change under it. What it shows is read once, when the screens
 * open: it names the profile the user came from, whatever happens to it meanwhile.
 *
 * @param purpose adding a profile, or signing in again to one.
 */
@HiltViewModel(assistedFactory = SignInOverProfileViewModel.Factory::class)
class SignInOverProfileViewModel @AssistedInject constructor(
    private val profiles: ProfileStore,
    @Assisted purpose: SignInPurpose,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(purpose: SignInPurpose): SignInOverProfileViewModel
    }

    private val openedOver: String? = profiles.current.value?.id

    val state = SignInOverProfileUiState(
        current = profiles.current.value?.label(),
        again = (purpose as? SignInPurpose.Again)
            ?.let { again -> profiles.profiles.value.firstOrNull { it.id == again.profileId } }
            ?.label(),
    )

    private val leftChannel = Channel<Unit>(Channel.BUFFERED)

    /** Emits when the screens are to leave by themselves, back to the profile they were opened over. */
    val left: Flow<Unit> = leftChannel.receiveAsFlow()

    /**
     * A sign-in succeeded, and its session is saved: the registry already names the profile it made current.
     *
     * The same profile as before — it signed in again, or the account added was already this one: the screens
     * leave, and the user is back where they were. Another profile: nothing is done here. The whole signed-in
     * level, these screens with it, is taken down and built again for that profile, and a navigation asked
     * meanwhile would move a level that is leaving.
     */
    fun onSignedIn() {
        if (profiles.current.value?.id == openedOver) leftChannel.trySend(Unit)
    }
}
