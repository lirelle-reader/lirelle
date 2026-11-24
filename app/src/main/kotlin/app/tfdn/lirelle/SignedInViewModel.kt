package app.tfdn.lirelle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.ui.SignInNeeded
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** How long the marks are still followed with no screen showing them: a rotation does not start them again. */
private const val MarksLingerMillis = 5_000L

/**
 * What the signed-in level shows of its profile on every screen. Made inside that level, whose store is tied
 * to the profile: it is cleared with it, and never holds the objects of another.
 *
 * @property covers the loader of the profile's covers, with their cache.
 */
@HiltViewModel
class SignedInViewModel @Inject constructor(
    downloadMarks: DownloadMarks,
    val covers: ImageLoader,
    private val network: ProfileNetwork,
    signIn: ProfileSignIn,
) : ViewModel() {

    /**
     * The profile while its session waits for a sign-in, as its screens name it when they cannot reach its
     * server; null while it does not. Followed for as long as the level lives: the level is the profile's.
     */
    val signInNeeded: StateFlow<SignInNeeded?> = signIn.needed
        .map { signIn.named(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, signIn.named(signIn.needed.value))

    /**
     * Whether the profile has a network: when its screens try again. The profile's, not the device's: it has
     * none while its session waits for a sign-in.
     */
    val online: StateFlow<Boolean> = network.online

    /**
     * Whether the device has no network at the moment this is read: the signed-in app then starts on what it
     * keeps. The device's, not the profile's ([online]): a profile that only waits for a sign-in starts on
     * Home, where it is told so and offered to sign in again — the downloaded books say nothing of it.
     */
    val deviceOffline: Boolean get() = !network.deviceOnline

    /** The mark of each book the device keeps something of: what the covers of the signed-in app carry. */
    val marks: StateFlow<Map<BookId, DownloadMark>> = downloadMarks.marks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(MarksLingerMillis), emptyMap())
}

/** The profile as its screens name it while it waits for a sign-in ([needed]); null while it does not. */
private fun ProfileSignIn.named(needed: Boolean): SignInNeeded? =
    if (needed) SignInNeeded(profileId, username, kind) else null
