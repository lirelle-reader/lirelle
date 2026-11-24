package app.tfdn.lirelle.signout

import android.content.Context
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.di.AccountDataOnDevice
import app.tfdn.lirelle.until
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.Instant

/**
 * The server of the accounts of these tests. Nothing listens there, and it is this machine: what the profile
 * sends is refused at once, as on a device without a network, and waits in the profile's stores.
 */
internal const val TestServer = "https://127.0.0.1:9"

/**
 * Signs out of whoever is current at this moment, as the account screen does: a sign-out names the profile it
 * has just read as the current one. Nothing when nobody is.
 */
internal suspend fun SessionStore.clearCurrent(reason: SignOutReason) {
    current.value?.let { clear(it.id, reason) }
}

/**
 * The device of a test of signing out: the application's real graph, an account signed in and out of it as
 * the sign-in screen and the account screen do it, and what the device still holds of profiles afterwards.
 */
internal class TestDevice(private val context: Context) : ProfileView {

    val data: AccountDataOnDevice by lazy {
        EntryPointAccessors.fromApplication(context, AccountDataOnDevice::class.java)
    }

    private val roots = listOf(File(context.filesDir, "profiles"), File(context.cacheDir, "profiles"))

    /** An entry point of the open profile, as a screen of the signed-in app reaches it. */
    inline fun <reified T : Any> profile(): T = data.currentProfile().entry<T>()

    override fun <T : Any> entry(type: Class<T>): T = data.currentProfile().entry(type)

    /** The folders of the profiles the device still has, under its files and under its cache. */
    fun profileFolders(): List<File> = roots.flatMap { it.listFiles().orEmpty().asList() }

    /** What the folders of every profile hold at their top, whichever profile and root they are in. */
    override fun stores(): List<File> = profileFolders().flatMap { it.listFiles().orEmpty().asList() }

    /** Every id the registry still lists, the profiles being removed among them. */
    suspend fun listed(): Set<String>? = data.profileStore().listedIds()

    /** The address the sign-in screen offers while nobody is signed in; null when someone is. */
    fun serverOffered(): String? = (data.sessions().state.value as? SessionState.SignedOut)?.serverUrl

    /**
     * Signs the account [userId] in. Signed in is when the profile's objects are there, as for the screens.
     *
     * @return the id of the profile opened for it.
     */
    suspend fun signIn(userId: Long): String {
        data.sessions().save(session(userId))
        return awaited("the profile of the account $userId is shown") {
            val (state, profileId) = data.openProfiles().shown.value
            profileId.takeIf { state is SessionState.SignedIn && state.session.user.id == "$userId" }
        }
    }

    /**
     * Signs out, and waits for the profile to be gone: the application closes it, deletes its folders and
     * drops its record on its own, in the background, as on a device. Part of the closing is done on the main
     * thread, which is the test's.
     */
    suspend fun signOut() {
        data.sessions().clearCurrent(SignOutReason.UserRequest)
        until("the profile signed out of is closed, its folders deleted and its record dropped") { gone() }
    }

    /** The record is dropped last, after the folders: nothing listed is everything done. */
    private suspend fun gone(): Boolean =
        data.openProfiles().current.value == null && profileFolders().isEmpty() && listed()?.isEmpty() == true

    private fun session(userId: Long) = bookOrbitSession(
        serverUrl = TestServer,
        sessionId = userId,
        tokens = Tokens("access-$userId", Instant.MAX, "refresh-$userId", Instant.MAX),
        user = UserProfile("$userId", "user$userId", "User $userId", email = null),
    )
}
