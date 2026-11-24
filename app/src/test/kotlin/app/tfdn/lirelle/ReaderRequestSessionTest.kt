package app.tfdn.lirelle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ShownSession
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.navigation.DownloadsRequests
import app.tfdn.lirelle.navigation.ReaderRequests
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A reader asked for is one of the signed-in account's books, and the downloads asked for are that account's:
 * neither carries over into another session.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderRequestSessionTest {

    @get:Rule
    val compose = createComposeRule()

    private val requests = ReaderRequests()
    private val downloads = DownloadsRequests()
    private val signedOut = SessionState.SignedOut("https://books.example.com", reason = null)
    private val signedIn = SessionState.SignedIn(
        bookOrbitSession(
            serverUrl = "https://books.example.com",
            sessionId = 1,
            tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
            user = UserProfile("1", "reader", "Ann Reader", null),
        ),
    )

    private var session: SessionState by mutableStateOf(SessionState.Loading)
    private var switching by mutableStateOf(false)

    private fun show() = compose.setContent {
        val shown = ShownSession(session, profileId = null, switching = switching)
        ForgetReaderRequestsWithoutProfile(shown, requests)
        ForgetDownloadsRequestsWithoutProfile(shown, downloads)
    }

    @Test
    fun `a change of profile drops what was asked of the profile left, and what is asked meanwhile`() {
        session = signedIn
        requests.open(BookId("42"))
        downloads.open()
        show()

        session = SessionState.Loading
        switching = true
        compose.waitForIdle()
        assertThat(requests.pending).isNull()
        assertThat(downloads.pending).isFalse()

        compose.runOnIdle { requests.open(BookId("43")) }
        compose.waitForIdle()
        assertThat(requests.pending).isNull()
    }

    @Test
    fun `a request waits while the session loads, and stays once signed in`() {
        requests.open(BookId("42"))
        show()
        compose.waitForIdle()
        assertThat(requests.pending).isEqualTo(BookId("42"))

        session = signedIn
        compose.waitForIdle()

        assertThat(requests.pending).isEqualTo(BookId("42"))
    }

    @Test
    fun `signing out drops the request`() {
        session = signedIn
        requests.open(BookId("42"))
        show()

        session = signedOut
        compose.waitForIdle()

        assertThat(requests.pending).isNull()
    }

    @Test
    fun `a request made while signed out is dropped`() {
        session = signedOut
        show()

        compose.runOnIdle { requests.open(BookId("42")) }
        compose.waitForIdle()

        assertThat(requests.pending).isNull()
    }

    @Test
    fun `the downloads asked for wait while the session loads, and stay once signed in`() {
        downloads.open()
        show()
        compose.waitForIdle()
        assertThat(downloads.pending).isTrue()

        session = signedIn
        compose.waitForIdle()

        assertThat(downloads.pending).isTrue()
    }

    @Test
    fun `signing out drops the downloads asked for, and those asked for then`() {
        session = signedIn
        downloads.open()
        show()

        session = signedOut
        compose.waitForIdle()
        assertThat(downloads.pending).isFalse()

        compose.runOnIdle { downloads.open() }
        compose.waitForIdle()
        assertThat(downloads.pending).isFalse()
    }
}
