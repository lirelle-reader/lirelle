package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

private const val SessionId = "session-hold-test"

/** A library service that gives its session as the playback service does: only while it is held. */
@androidx.annotation.OptIn(UnstableApi::class)
class GivingWayService : MediaLibraryService() {
    internal lateinit var hold: SessionHold
    internal lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        session = MediaLibrarySession.Builder(this, FakeEngine(), object : MediaLibrarySession.Callback {})
            .setId(SessionId)
            .build()
        hold = SessionHold(this, session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = hold.held

    override fun onDestroy() {
        hold.release()
        super.onDestroy()
    }
}

/** The service's session let go when its profile closes: once, and nobody is given it after. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SessionHoldTest {

    private val controller = Robolectric.buildService(GivingWayService::class.java).create()
    private val service = controller.get()
    private var destroyed = false

    @After
    fun end() {
        if (!destroyed) controller.destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Whether the service's session was released, as anyone can tell: a session's id is its own until it is
     * released, and no other can be built with it before.
     */
    private fun sessionReleased(): Boolean =
        runCatching { MediaSession.Builder(service, FakeEngine()).setId(SessionId).build().release() }.isSuccess

    @Test
    fun `held, the session is the one given to a controller`() {
        assertThat(service.hold.held).isSameInstanceAs(service.session)
        assertThat(service.hold.released).isFalse()
        assertThat(sessionReleased()).isFalse()
    }

    @Test
    fun `let go, the session is released and a controller asking for one gets none`() {
        service.hold.release()
        idle()

        assertThat(sessionReleased()).isTrue()
        assertThat(service.hold.released).isTrue()
        assertThat(service.hold.held).isNull()
    }

    /** A controller had been given it: the service knows the session, and must forget it. */
    @Test
    fun `a session the service had given is taken out of it before it is released`() {
        service.addSession(service.session)
        assertThat(service.isSessionAdded(service.session)).isTrue()

        service.hold.release()
        idle()

        assertThat(service.isSessionAdded(service.session)).isFalse()
        assertThat(service.sessions).isEmpty()
        assertThat(sessionReleased()).isTrue()
    }

    @Test
    fun `let go twice, nothing more happens`() {
        service.addSession(service.session)

        service.hold.release()
        service.hold.release()
        idle()

        assertThat(sessionReleased()).isTrue()
    }

    /** What the service still says to its session after it left goes nowhere. */
    @Test
    fun `once let go, what is said to the session through the hold reaches nobody`() {
        service.hold.release()

        service.hold.held?.setSessionExtras(Bundle())
        idle()

        assertThat(service.hold.held).isNull()
    }

    @Test
    fun `the service ending after the session was let go releases nothing twice`() {
        service.addSession(service.session)
        service.hold.release()
        idle()

        controller.destroy()
        destroyed = true
        idle()

        assertThat(sessionReleased()).isTrue()
    }

    @Test
    fun `the service ending lets go of a session still held`() {
        controller.destroy()
        destroyed = true
        idle()

        assertThat(sessionReleased()).isTrue()
    }
}
