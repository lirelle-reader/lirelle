package app.tfdn.lirelle.profile

import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.ui.ProfileAvatarModel
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentProfileAvatarTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val current = MutableStateFlow<Profile?>(null)

    private fun profile(id: String, username: String, signInNeeded: Boolean = false) = Profile(
        id = id,
        kind = ServerKind.BookOrbit,
        session = bookOrbitSession(
            serverUrl = "https://books.example.org",
            sessionId = 1,
            tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
            user = UserProfile("1", username, "Somebody", email = null),
        ),
        signInNeeded = signInNeeded,
    )

    private fun TestScope.avatars() = FileProfileAvatars(folder.newFolder(), UnconfinedTestDispatcher(testScheduler))

    /** What each picture told held when it was: a later one takes its place on the disk. */
    private val pictures = mutableListOf<Byte?>()

    /** Every avatar told from now on, in order. */
    private fun TestScope.told(avatars: FileProfileAvatars): List<ProfileAvatarModel?> {
        val told = mutableListOf<ProfileAvatarModel?>()
        backgroundScope.launch {
            currentAvatar(current, avatars).collect {
                told += it
                pictures += it?.picture?.readBytes()?.single()
            }
        }
        return told
    }

    @Test
    fun `nobody current has no avatar`() = runTest(UnconfinedTestDispatcher()) {
        assertThat(told(avatars())).containsExactly(null)
    }

    @Test
    fun `the current profile is drawn with the picture the device keeps of it, as it changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val avatars = avatars()
            current.value = profile("p0", "camille")
            val told = told(avatars)

            avatars.keep("p0", "/avatar?v=1", byteArrayOf(1))
            avatars.keep("p0", "/avatar?v=2", byteArrayOf(2))
            avatars.delete("p0")

            assertThat(told.map { it?.initial }).containsExactly("C", "C", "C", "C")
            assertThat(pictures).containsExactly(null, 1.toByte(), 2.toByte(), null).inOrder()
            // The picture that replaced the first is not an equal avatar: it is drawn again.
            assertThat(told[2]).isNotEqualTo(told[1])
        }

    @Test
    fun `it follows the profile that is current, its mark and its own picture`() = runTest(UnconfinedTestDispatcher()) {
        val avatars = avatars()
        avatars.keep("p1", "/avatar?v=1", byteArrayOf(7))
        current.value = profile("p0", "camille")
        val told = told(avatars)

        current.value = profile("p0", "camille", signInNeeded = true)
        current.value = profile("p1", "test")

        assertThat(told.map { it?.seed to it?.signInNeeded })
            .containsExactly("p0" to false, "p0" to true, "p1" to false).inOrder()
        assertThat(told.last()?.initial).isEqualTo("T")
        assertThat(pictures.last()).isEqualTo(7.toByte())
    }
}
