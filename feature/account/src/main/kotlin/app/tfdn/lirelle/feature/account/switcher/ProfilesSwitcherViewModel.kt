package app.tfdn.lirelle.feature.account.switcher

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.core.ui.ProfileAvatarModel
import app.tfdn.lirelle.core.ui.avatarModel
import app.tfdn.lirelle.core.ui.nameRes
import app.tfdn.lirelle.core.ui.profileLine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the profiles are still followed once nothing shows them: a rotation does not start it over. */
internal const val KeepFollowingMillis = 5_000L

/** What an address starts with before its host: `https://`, in any case. */
private val Scheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/**
 * A profile as the profile button and the list of profiles show it.
 *
 * @property line "`<username>` · `<kind>`".
 * @property username the account's, on its own: the button says it apart from the kind.
 * @property kindName the kind of its server, in words.
 * @property kind the kind of its server: what its removal does on the server depends on it.
 * @property server the server's address without its scheme, which says nothing to who reads it.
 * @property current it is the profile the app is on.
 * @property signInNeeded it waits for its account to sign in again.
 */
data class ProfileRow(
    val id: String,
    val line: String,
    val username: String,
    val kindName: String,
    val kind: ServerKind,
    val server: String,
    val avatar: ProfileAvatarModel,
    val current: Boolean,
    val signInNeeded: Boolean,
)

/**
 * The profiles of the device, in order of use, the current one first.
 *
 * @property rows one per profile that may be opened.
 */
data class SwitcherState(val rows: List<ProfileRow> = emptyList()) {
    /** The profile the app is on; null while nobody is signed in. */
    val current: ProfileRow? get() = rows.firstOrNull { it.current }

    /** Whether there is somebody to switch to: with one profile there is no profile button. */
    val severalProfiles: Boolean get() = rows.size >= 2
}

/** [address] as it is shown: without its scheme, nor a slash at its end; its port and its path are kept. */
internal fun serverShown(address: String): String = address.trim().replace(Scheme, "").trimEnd('/')

/** What the list shows of [profile], with the [picture] the device keeps of its account. */
internal fun Profile.row(resources: Resources, picture: File?, current: Boolean) = ProfileRow(
    id = id,
    line = profileLine(resources, session.user.username, kind),
    username = session.user.username,
    kindName = resources.getString(kind.nameRes),
    kind = kind,
    server = serverShown(session.serverUrl),
    avatar = avatarModel(picture),
    current = current,
    signInNeeded = signInNeeded,
)

/**
 * The profiles for the profile button and the list it opens, and the switch to another.
 *
 * It reads the device's data only: the registry and the pictures the device keeps, which are there whoever
 * is current, and nothing of the open profile's own objects. That is why it may ask for a switch: nothing it
 * holds is closed with the profile left. It does not outlive the switch, though: it is made in the signed-in
 * level, whose view models are thrown away when the profile changes, and the next profile's level makes its
 * own.
 *
 * @param context the application's, for the two texts of a row that are not the account's: the name of the
 *   kind of server and the sign between it and the username, which no language changes.
 */
@HiltViewModel
class ProfilesSwitcherViewModel @Inject constructor(
    private val store: ProfileStore,
    avatars: ProfileAvatars,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val resources = context.resources

    /** Starts on the initials, at once: the pictures are read from the disk, and come after. */
    val state: StateFlow<SwitcherState> = store.rows(avatars, resources).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(KeepFollowingMillis),
        store.rowsNow(resources),
    )

    /**
     * Makes the profile [id] the current one. The app then leaves the profile it is on — this view model
     * with it — and shows a wait until the other is open.
     */
    fun switchTo(id: String) {
        viewModelScope.launch { store.switchTo(id) }
    }
}

/** The profiles of the registry as they are at this moment, on their initials: no picture is read. */
internal fun ProfileStore.rowsNow(resources: Resources): SwitcherState =
    SwitcherState(profiles.value.map { it.row(resources, picture = null, current.value?.id == it.id) })

/**
 * The profiles of the registry as they change, each with the picture the device keeps of its account. For
 * whoever lists the profiles from the device's side: the profile button and the Profiles page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ProfileStore.rows(avatars: ProfileAvatars, resources: Resources): Flow<SwitcherState> =
    combine(profiles, current) { profiles, current -> profiles to current?.id }
        .flatMapLatest { (profiles, currentId) ->
            if (profiles.isEmpty()) {
                flowOf(SwitcherState())
            } else {
                combine(profiles.map { avatars.picture(it.id) }) { pictures ->
                    SwitcherState(
                        profiles.mapIndexed { at, profile ->
                            profile.row(resources, pictures[at], profile.id == currentId)
                        },
                    )
                }
            }
        }
