package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.feature.account.switcher.KeepFollowingMillis
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.rows
import app.tfdn.lirelle.feature.account.switcher.rowsNow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The current profile as the registry knows it: what the page shows of it with or without its server. Not the
 * open profile itself (`core.session.profile.CurrentProfile`), only what is written of it.
 *
 * @property row what every list of profiles shows of it.
 * @property name the account's name.
 * @property email the account's e-mail address; null when it has none, and its row is then left out.
 * @property serverUrl its server's address, whole.
 */
data class CurrentProfileDetails(val row: ProfileRow, val name: String, val email: String?, val serverUrl: String)

/**
 * The profiles of the device for the Profiles page.
 *
 * @property current the profile the app is on; null while nobody is signed in, or the page is being left
 *   with its profile.
 * @property others the profiles that sleep, in order of use.
 */
data class ProfilesPageState(val current: CurrentProfileDetails? = null, val others: List<ProfileRow> = emptyList())

/** The page's state from the [rows] of the registry and the profile it says is the [current] one. */
internal fun pageState(rows: List<ProfileRow>, current: Profile?): ProfilesPageState {
    val row = rows.firstOrNull { it.current && it.id == current?.id }
    val user = current?.session?.user
    return ProfilesPageState(
        current = if (row != null && user != null) {
            CurrentProfileDetails(row, user.name, user.email, current.session.serverUrl)
        } else {
            null
        },
        others = rows.filterNot { it.current },
    )
}

/**
 * The profiles for the Profiles page, the switch to another and the removal of one that sleeps.
 *
 * It belongs to the device, not to a profile, as the profile button's does: it reads the registry and the
 * pictures the device keeps, and nothing of the open profile's own objects. What the page shows of the
 * current profile's server, and the removal of the current profile, are [AccountViewModel]'s.
 *
 * A profile that sleeps is removed from the registry, none of its objects existing; then its credential is handed
 * to its kind ([AuthRepository.endCredential]), which ends it on its server as it must: BookOrbit sends nothing
 * (its session expires, its tokens go with its folders), Komga revokes the key, as will Kavita one the app
 * made. The removal does not wait for the server: it is done first.
 */
@HiltViewModel
class ProfilesPageViewModel @Inject constructor(
    private val store: ProfileStore,
    private val auth: AuthRepository,
    avatars: ProfileAvatars,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val resources = context.resources

    /** Starts on the initials, at once: the pictures are read from the disk, and come after. */
    val state: StateFlow<ProfilesPageState> =
        combine(store.rows(avatars, resources), store.current) { rows, current -> pageState(rows.rows, current) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(KeepFollowingMillis),
                pageState(store.rowsNow(resources).rows, store.current.value),
            )

    private val asked = MutableStateFlow<String?>(null)

    /**
     * The id of the sleeping profile whose removal is being confirmed; null when none is, and as soon as
     * that profile is no longer one that sleeps — removed meanwhile, or become the current one: the question
     * then closes by itself.
     */
    val removal: StateFlow<String?> = combine(asked, store.profiles, store.current) { id, profiles, current ->
        id.takeIf { sleeps(it, profiles, current) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Makes the profile [id] the current one. The app then leaves the profile it is on, and this page. */
    fun switchTo(id: String) {
        viewModelScope.launch { store.switchTo(id) }
    }

    /** Nothing is done yet: the user is asked first. */
    fun onAskRemove(id: String) {
        asked.value = id
    }

    fun onDismissRemove() {
        asked.value = null
    }

    /**
     * The user confirmed. The answer is taken once: a second tap finds no question. Nothing happens when the
     * profile is gone, or is the current one by now — that one is removed by signing it out, which this
     * does not do. The registry decides it again when it applies the removal ([ProfileStore.removeSleeping]):
     * a switch may still land between the look here and the change there.
     */
    fun onRemove() {
        val id = asked.getAndUpdate { null } ?: return
        val profiles = store.profiles.value
        if (!sleeps(id, profiles, store.current.value)) return
        // Its credential read before it goes: no profile will be open to hand it over.
        val removed = profiles.first { it.id == id }
        viewModelScope.launch {
            // To the end once the user answered, the page left or not.
            withContext(NonCancellable) {
                if (store.removeSleeping(id)) {
                    auth.endCredential(removed.kind, removed.session.serverUrl, removed.session.credential)
                }
            }
        }
    }

    private fun sleeps(id: String?, profiles: List<Profile>, current: Profile?): Boolean =
        id != null && id != current?.id && profiles.any { it.id == id }
}
