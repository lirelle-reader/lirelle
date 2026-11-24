package app.tfdn.lirelle.core.ui

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import org.junit.Test

class ProfileAvatarModelsTest {

    private fun profile(username: String, signInNeeded: Boolean = false) = Profile(
        id = "3fa9c1d2",
        kind = ServerKind.BookOrbit,
        session = bookOrbitSession(
            serverUrl = "https://books.example.org",
            sessionId = 1,
            tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
            user = UserProfile("1", username, "Camille Martin", email = null),
        ),
        signInNeeded = signInNeeded,
    )

    @Test
    fun `a profile is drawn by its id, the initial of its username and the letter of its kind`() {
        val picture = File("/kept/3fa9c1d2")

        assertThat(profile("camille").avatarModel(picture))
            .isEqualTo(ProfileAvatarModel("3fa9c1d2", "C", "B", picture, signInNeeded = false))
        assertThat(profile("camille", signInNeeded = true).avatarModel(picture = null))
            .isEqualTo(ProfileAvatarModel("3fa9c1d2", "C", "B", picture = null, signInNeeded = true))
    }

    @Test
    fun `a Komga profile has the letter K, and the initial of its account's email`() {
        val komga = Profile(
            id = "7b20e4f1",
            kind = ServerKind.Komga,
            session = Session(
                serverUrl = "https://bd.example.org",
                credential = Credential.ApiKey(key = "secret", keyId = "0K3Y"),
                user = UserProfile("0B5Y", "camille@example.org", "camille@example.org", "camille@example.org"),
            ),
        )

        assertThat(komga.avatarModel(picture = null))
            .isEqualTo(ProfileAvatarModel("7b20e4f1", "C", "K", picture = null, signInNeeded = false))
    }

    @Test
    fun `the initial is the first letter of the username, upper case, whatever it is written with`() {
        assertThat(initialOf("camille")).isEqualTo("C")
        assertThat(initialOf("  élodie")).isEqualTo("É")
        assertThat(initialOf("42")).isEqualTo("4")
        // One letter written with two code units is not cut in two.
        assertThat(initialOf("\uD835\uDC1Ebc")).isEqualTo("\uD835\uDC1E")
    }

    @Test
    fun `a username that is empty has a question mark for an initial`() {
        assertThat(initialOf("")).isEqualTo("?")
        assertThat(initialOf("   ")).isEqualTo("?")
    }
}
