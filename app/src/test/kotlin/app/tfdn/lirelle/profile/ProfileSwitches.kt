package app.tfdn.lirelle.profile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.ProfileGraph
import app.tfdn.lirelle.feature.login.ServerViewModel
import app.tfdn.lirelle.feature.login.SignInViewModel
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.until
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * What every profile follows of the device once it has started: the five follow-ups of the profile's starters
 * (`ProfileStartersModule`). A profile that has that many followers is started; twice as many are two profiles.
 */
internal const val FollowsOfAStartedProfile = 5

/** The device has a network: what a profile does not send from here on, it holds back for another reason. */
internal fun Context.deviceOnline() {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val capabilities = ShadowNetworkCapabilities.newInstance()
    shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
}

/** The profile the app has open, once it shows the profile [id]: its objects, as the screens reach them. */
internal suspend fun TestDevice.shows(id: String): GraphView {
    until("the profile $id is the one shown, with its own objects") {
        val shown = data.openProfiles().shown.value
        shown.profileId == id && shown.state is SessionState.SignedIn &&
            data.openProfiles().current.value?.profileId == id
    }
    return GraphView(checkNotNull(data.openProfiles().current.value))
}

/**
 * Makes the profile [id] the current one as a tap on it does — `ProfileStore.switchTo` and nothing else — and
 * waits for the app, which closes the profile left and opens this one on its own threads.
 */
internal suspend fun TestDevice.switchesTo(id: String): GraphView {
    data.profileStore().switchTo(id)
    return shows(id)
}

/** The open profile's graph, or null: what [app.tfdn.lirelle.core.session.profile.OpenProfiles.current] says. */
internal fun TestDevice.open(): ProfileGraph? = data.openProfiles().current.value

/**
 * Signs the account [userId] in on [server] through the sign-in screens' own view models, with the device's
 * real sign-in behind them: the address typed and checked, the methods the server offers, the password sent,
 * the session saved. Nobody signed in or somebody, it is the same path: over a profile, this adds one.
 *
 * @param server one that signs in ([RecordingServer.signsIn]).
 * @return the profile the app shows once that account has signed in.
 */
internal suspend fun TestDevice.signsInOn(server: RecordingServer, userId: Long): GraphView {
    val auth = data.auth()
    val serverStep = ServerViewModel(auth)
    serverStep.onAddressChange(server.address)
    serverStep.onContinue()
    // The test's server speaks without TLS, which the user is asked to accept.
    serverStep.onCleartextConfirmed()
    until("the address typed is checked and remembered") { auth.serverUrl.value == server.address }
    val step = SignInViewModel(auth, data.oidcRedirects(), null, null)
    until("the server's sign-in methods are offered") { step.state.value.options != null }
    coroutineScope {
        val told = async(start = CoroutineStart.UNDISPATCHED) { step.signedIn.first() }
        step.onUsernameChange("user$userId")
        step.onPasswordChange("secret")
        step.onSignIn()
        until("the sign-in of the account $userId has succeeded") { told.isCompleted }
    }
    // The screens leave: their view models are cleared.
    serverStep.viewModelScope.cancel()
    step.viewModelScope.cancel()
    val saved = checkNotNull(data.profileStore().current.value) { "Nobody is current after a sign-in" }
    check(saved.session.user.id == "$userId") { "The sign-in saved another account" }
    return shows(saved.id)
}
