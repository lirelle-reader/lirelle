package app.tfdn.lirelle

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.session.profile.OpenProfiles
import app.tfdn.lirelle.core.session.profile.ShownSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app follows whoever is signed in, and while nobody is: it lives in the activity's store, outside
 * the levels. It therefore takes nothing of a profile — it would outlive it, and there is none to take from
 * at a signed-out start: what the signed-in level needs of its profile is [SignedInViewModel]'s.
 */
@HiltViewModel
class RootViewModel @Inject constructor(profiles: OpenProfiles) : ViewModel() {
    /**
     * The session as the screens follow it, with the id of the profile the signed-in level is tied to: signed
     * in only once the profile's objects are there for them, signed out before those are thrown away — or
     * switching, while another profile takes its place. The id only: the profile's objects are not for a
     * screen to hold.
     */
    val shown: StateFlow<ShownSession> = profiles.shown
}
