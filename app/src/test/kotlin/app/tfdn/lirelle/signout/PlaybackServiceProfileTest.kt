package app.tfdn.lirelle.signout

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.di.AccountDataOnDevice
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.player.service.PlaybackButtonReceiver
import app.tfdn.lirelle.feature.player.service.PlaybackService
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import app.tfdn.lirelle.feature.player.service.PlayerNotification
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

private const val Server = "https://books.example"

/** The action the system's media controls bind the service with: the session is asked for at once. */
private const val BrowserAction = "android.media.browse.MediaBrowserService"

/**
 * The playback service and the profile it plays for, on the application's real graph: the system creates the
 * service when it wants to, whoever is signed in, or nobody; and an account that leaves must not be heard, nor
 * seen in the player, by the next one.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceProfileTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device by lazy { EntryPointAccessors.fromApplication(context, AccountDataOnDevice::class.java) }
    private val made = mutableListOf<ServiceController<PlaybackService>>()

    /** The start the media button receiver makes for a headset's play button: it owes the foreground. */
    private val playButton = PlaybackButtonReceiver.markPlayButtonStart(Intent(Intent.ACTION_MEDIA_BUTTON))

    /** The service, created as the system creates it. */
    private fun create(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).also { made += it }.create().get()

    /** The session a controller is given, as the system's media controls ask for it; null when refused. */
    private fun PlaybackService.session() = onBind(Intent(BrowserAction))

    private fun Service.enteredForeground() = shadowOf(this).lastForegroundNotificationId != 0

    private fun Service.leftForeground() = shadowOf(this).isForegroundStopped

    private fun Service.stopped() = shadowOf(this).isStoppedBySelf

    private suspend fun signIn() {
        val user = UserProfile("1", "user1", "User 1", email = null)
        device.sessions().save(bookOrbitSession(Server, 1, Tokens("access", Instant.MAX, "refresh", Instant.MAX), user))
        until("the profile signed in to is shown") { device.openProfiles().shown.value.state is SessionState.SignedIn }
    }

    /** The profile closes as it does on a device, on another thread than the service's. */
    private suspend fun signOut() {
        device.sessions().clearCurrent(SignOutReason.UserRequest)
        until("the profile signed out of is closed") { device.openProfiles().current.value == null }
    }

    /** The system ends a service that stopped itself and that nothing is bound to. */
    @After
    fun endServices() {
        made.forEach { it.destroy() }
        assertThat(PlaybackServiceRunning.running.value).isFalse()
    }

    @Test
    fun `with nobody signed in, the service is made of nothing and is not stopped as it is created`() {
        val service = create()

        // A start that owes the foreground may be on its way: stopping here would end the app.
        assertThat(service.stopped()).isFalse()
        assertThat(PlaybackServiceRunning.running.value).isFalse()
        assertWithMessage("session").that(service.session()).isNull()
    }

    @Test
    fun `with nobody signed in, a start is answered by stopping, and owes nothing`() {
        val service = create()

        val answer = service.onStartCommand(Intent(), 0, 1)

        assertThat(answer).isEqualTo(Service.START_NOT_STICKY)
        assertThat(service.stopped()).isTrue()
        assertThat(service.enteredForeground()).isFalse()
    }

    /** The profile closed between the play button and the service: the system ends an app that owes it. */
    @Test
    fun `with nobody signed in, a play button's start is paid the foreground it owes, then the service stops`() {
        val service = create()

        val answer = service.onStartCommand(playButton, 0, 1)

        assertThat(answer).isEqualTo(Service.START_NOT_STICKY)
        assertThat(service.enteredForeground()).isTrue()
        assertThat(service.leftForeground()).isTrue()
        assertThat(service.stopped()).isTrue()
    }

    @Test
    fun `with nobody signed in, the service ends and is swiped away without a word`() {
        val service = create()

        service.onTaskRemoved(null)
        made.removeAt(0).destroy()

        assertThat(service.stopped()).isFalse()
    }

    /** It names a book of the account that leaves, and a tap would open that id for the next one. */
    @Test
    fun `what asked for a tap to continue listening goes when the profile closes`() = runBlocking {
        signIn()
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val asking = PlayerNotification.continueNotification(context, "The Long Road", null)
        manager.notify(PlayerNotification.ContinueId, asking)

        signOut()
        until("the notification that asked for a tap is gone") {
            shadowOf(manager).getNotification(PlayerNotification.ContinueId) == null
        }

        assertThat(shadowOf(manager).getNotification(PlayerNotification.ContinueId)).isNull()
    }

    @Test
    fun `it plays for the profile open when it is made, and ends when that profile closes`() = runBlocking {
        signIn()
        val service = create()
        service.onStartCommand(Intent(), 0, 1)
        assertThat(PlaybackServiceRunning.running.value).isTrue()
        assertWithMessage("session").that(service.session()).isNotNull()
        assertThat(service.stopped()).isFalse()

        signOut()
        until("the service stops itself") { service.stopped() }

        assertThat(service.stopped()).isTrue()
        assertWithMessage("session for the next account").that(service.session()).isNull()
    }

    /** The closing gives a service a few seconds and goes on: one it did not reach must not stay. */
    @Test
    fun `a service the closing of its profile never told ends by itself`() = runBlocking {
        signIn()
        val service = create()
        service.onStartCommand(Intent(), 0, 1)
        // As if the profile had closed before the service was known to it.
        device.currentProfile().entry<PlayerProfileEntry>().playbackParts().shutdown.unregister()

        signOut()
        until("the service stops itself") { service.stopped() }

        assertThat(service.stopped()).isTrue()
        assertWithMessage("session for the next account").that(service.session()).isNull()
    }

    @Test
    fun `a play button's start that comes after its profile closed is paid, then the service stops`() = runBlocking {
        signIn()
        val service = create()

        signOut()
        until("the service gives no session any more") { service.session() == null }
        // Only bound so far: stopping before the start is delivered would leave the foreground unpaid.
        assertThat(service.stopped()).isFalse()
        service.onStartCommand(playButton, 0, 1)

        assertThat(service.enteredForeground()).isTrue()
        assertThat(service.leftForeground()).isTrue()
        assertThat(service.stopped()).isTrue()
    }
}
