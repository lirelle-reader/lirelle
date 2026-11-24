package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.clearCurrent
import app.tfdn.lirelle.core.session.remove
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val Fetched = "/api/v1/users/1/avatar?v=1"

/**
 * The picture of a profile's account goes when its folders go, and only then: with a profile that is removed,
 * open or asleep, and at a start for the profiles nobody lists. A profile that is left, or that waits for a
 * sign-in, keeps it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphsPicturesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }

    private class Built(val session: ProfileSession, val scope: CoroutineScope) : ProfileComponent

    private val built = mutableListOf<Built>()

    private val life = object : ProfileLifeEntry {
        override fun starters() = emptySet<ProfileStarter>()

        override fun closers() = emptySet<ProfileCloser>()
    }

    private fun TestScope.graphs(registry: FakeRegistry) = ProfileGraphs(
        registry,
        registry,
        roots,
        avatars,
        GraphMaking(
            build = { session, _, scope -> Built(session, scope).also { built += it } },
            lifeOf = { life },
            warn = { _, _ -> },
            graph = UnconfinedTestDispatcher(testScheduler),
            io = UnconfinedTestDispatcher(testScheduler),
        ),
        registry,
    )

    private suspend fun keepPictures(vararg ids: String) = ids.forEach { avatars.keep(it, Fetched, byteArrayOf(1)) }

    private suspend fun kept(vararg ids: String) = ids.filter { avatars.keptFor(it) != null }

    @Test
    fun `the open profile removed loses its picture with its folders`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))
        keepPictures("p7", "p8")
        graphs(registry).open(backgroundScope)

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(kept("p7", "p8")).containsExactly("p8")
        assertThat(registry.listedIds()).containsExactly("p8")
    }

    @Test
    fun `a sleeping profile removed loses its picture`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))
        keepPictures("p7", "p8")
        graphs(registry).open(backgroundScope)

        registry.remove("p8", SignOutReason.UserRequest)

        assertThat(kept("p7", "p8")).containsExactly("p7")
    }

    @Test
    fun `a profile left for another keeps its picture`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))
        keepPictures("p7", "p8")
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        registry.switchTo("p8")

        assertThat(graphs.current.value?.profileId).isEqualTo("p8")
        assertThat(kept("p7", "p8")).containsExactly("p7", "p8")
    }

    @Test
    fun `a profile that needs sign-in keeps its picture`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        keepPictures("p7")
        graphs(registry).open(backgroundScope)

        built.single().session.expire(session().credential)

        assertThat(registry.profile("p7")?.signInNeeded).isTrue()
        assertThat(kept("p7")).containsExactly("p7")
    }

    @Test
    fun `a start deletes the pictures of profiles that nobody lists`() = runTest(UnconfinedTestDispatcher()) {
        keepPictures("p7", "p8", "gone")
        val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))

        graphs(registry).open(backgroundScope)

        assertThat(kept("p7", "p8", "gone")).containsExactly("p7", "p8")
    }

    @Test
    fun `a start finishes the removal of a profile, its picture too`() = runTest(UnconfinedTestDispatcher()) {
        keepPictures("p7", "p6")
        val registry = FakeRegistry(stored = profile("p7"), leftBeingRemoved = listOf("p6"))

        graphs(registry).open(backgroundScope)

        assertThat(kept("p7", "p6")).containsExactly("p7")
        assertThat(registry.listedIds()).containsExactly("p7")
    }

    @Test
    fun `a registry that could not be read costs no profile its picture`() = runTest(UnconfinedTestDispatcher()) {
        // The file lists these profiles still; it could only not be read at this start.
        keepPictures("p7", "p8")

        graphs(FakeRegistry(unreadable = true)).open(backgroundScope)

        assertThat(kept("p7", "p8")).containsExactly("p7", "p8")
    }
}
