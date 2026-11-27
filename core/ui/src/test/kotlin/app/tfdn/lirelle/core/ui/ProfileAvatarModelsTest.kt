package app.tfdn.lirelle.core.ui

import androidx.compose.ui.graphics.Color
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlin.math.pow
import org.junit.Test

private val BookOrbitGround = Color(0xFFFAFAF8)
private val BookOrbitLetter = Color(0xFF1E1E18)

/** The relative luminance of an opaque colour, as WCAG 2 defines it: each channel made linear, then weighed. */
private fun Color.wcagLuminance(): Double {
    fun linear(channel: Float): Double =
        if (channel <= 0.03928f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}

/** The contrast ratio of two opaque colours, as WCAG 2 defines it: from 1 to 21. */
private fun contrast(one: Color, other: Color): Double {
    val lighter = maxOf(one.wcagLuminance(), other.wcagLuminance())
    val darker = minOf(one.wcagLuminance(), other.wcagLuminance())
    return (lighter + 0.05) / (darker + 0.05)
}

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
            .isEqualTo(ProfileAvatarModel("3fa9c1d2", "C", "B", BookOrbitGround, BookOrbitLetter, picture, false))
        assertThat(profile("camille", signInNeeded = true).avatarModel(picture = null))
            .isEqualTo(ProfileAvatarModel("3fa9c1d2", "C", "B", BookOrbitGround, BookOrbitLetter, null, true))
    }

    private fun keyed(kind: ServerKind) = Profile(
        id = "7b20e4f1",
        kind = kind,
        session = Session(
            serverUrl = "https://bd.example.org",
            credential = Credential.ApiKey(key = "secret", keyId = "0K3Y"),
            user = UserProfile("0B5Y", "camille@example.org", "camille@example.org", "camille@example.org"),
        ),
    )

    @Test
    fun `a Komga profile has the letter K, white on Komga's blue, and the initial of its account's email`() {
        assertThat(keyed(ServerKind.Komga).avatarModel(picture = null)).isEqualTo(
            ProfileAvatarModel("7b20e4f1", "C", "K", Color(0xFF005ED3), Color(0xFFFFFFFF), null, signInNeeded = false),
        )
    }

    @Test
    fun `a Kavita profile has the letter K too, dark on Kavita's green`() {
        assertThat(keyed(ServerKind.Kavita).avatarModel(picture = null)).isEqualTo(
            ProfileAvatarModel("7b20e4f1", "C", "K", Color(0xFF4AC694), Color(0xFF06281B), null, signInNeeded = false),
        )
    }

    private val rotating = Profile(
        id = "c41d9a07",
        kind = ServerKind.Audiobookshelf,
        session = Session(
            serverUrl = "https://audio.example.org",
            credential = Credential.RotatingTokens(
                signInId = "8c1f2b9e-4d7a-4f0e-b6a1-3e5d9c2f7a18",
                tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
            ),
            user = UserProfile("2f0c6b1e-7a3d-4c58-9e21-b4d8f6a0c937", "camille", "camille", email = null),
        ),
    )

    @Test
    fun `an Audiobookshelf profile has the letter A, amber on Audiobookshelf's dark grey`() {
        assertThat(rotating.avatarModel(picture = null)).isEqualTo(
            ProfileAvatarModel("c41d9a07", "C", "A", Color(0xFF232323), Color(0xFFCD9D49), null, signInNeeded = false),
        )
    }

    @Test
    fun `a model made without a profile has the badge of its kind`() {
        ServerKind.entries.forEach { kind ->
            val ofProfile = when (kind) {
                ServerKind.BookOrbit -> profile("camille")
                ServerKind.Komga, ServerKind.Kavita -> keyed(kind)
                ServerKind.Audiobookshelf -> rotating
            }.avatarModel(null)

            assertThat(profileAvatarModel(ofProfile.seed, "C", kind, picture = null, signInNeeded = false))
                .isEqualTo(ofProfile)
        }
    }

    @Test
    fun `the letter of every kind reads on its badge, at 4,5 to 1 or better`() {
        ServerKind.entries.forEach { kind ->
            val model = profileAvatarModel("p0", "C", kind, picture = null, signInNeeded = false)

            assertWithMessage("$kind").that(contrast(model.badgeContent, model.badgeGround)).isAtLeast(4.5)
        }
    }

    @Test
    fun `two kinds that share a letter do not share a badge`() {
        val badges = ServerKind.entries.map { kind ->
            profileAvatarModel("p0", "C", kind, picture = null, signInNeeded = false)
                .let { Triple(it.kindLetter, it.badgeGround, it.badgeContent) }
        }

        assertThat(badges.distinct()).hasSize(ServerKind.entries.size)
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
