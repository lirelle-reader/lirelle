package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Profile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The factory knows nothing of which profile is current: it makes the objects of any, and of two at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphFactoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }
    private val started = mutableListOf<CoroutineScope>()
    private val closed = mutableListOf<String>()

    /** The objects of a profile, as the test's `build` makes them: what they were built with. */
    private class Built(val session: ProfileSession, val folders: ProfileFolders, val scope: CoroutineScope) :
        ProfileComponent

    private fun TestScope.factory(registry: FakeRegistry) = ProfileGraphFactory(
        registry,
        roots,
        avatars,
        GraphMaking(
            build = ::Built,
            lifeOf = { component ->
                object : ProfileLifeEntry {
                    override fun starters() = setOf(ProfileStarter { started += it })

                    override fun closers() = setOf(ProfileCloser { closed += (component as Built).session.profileId })
                }
            },
            warn = { _, _ -> },
            graph = UnconfinedTestDispatcher(testScheduler),
            io = UnconfinedTestDispatcher(testScheduler),
        ),
        registry,
    )

    /** The objects of [profile], of the current format: there is nothing to start again, it always opens. */
    private suspend fun ProfileGraphFactory.opened(profile: Profile) = checkNotNull(open(profile))

    private fun foldersExist(id: String) = roots.of(id).let { it.files.exists() && it.cache.exists() }

    /** A registry that lists two ready profiles, p0 and p1 the current one: what several profiles will leave. */
    private suspend fun twoListed() = FakeRegistry(
        stored = profile("p1", userId = 2),
        alsoListed = listOf(profile("p0", userId = 1)),
    ).also { it.load() }

    @Test
    fun `two profiles are open at once, each with its own session, folders and scope`() = runTest {
        val registry = twoListed()
        val factory = factory(registry)

        val first = factory.opened(profile("p0", userId = 1))
        val second = factory.opened(profile("p1", userId = 2))

        val (one, other) = listOf(first, second).map { it.component as Built }
        assertThat(one.session.profileId).isEqualTo("p0")
        assertThat(other.session.profileId).isEqualTo("p1")
        assertThat(one.folders).isEqualTo(roots.of("p0"))
        assertThat(other.folders).isEqualTo(roots.of("p1"))
        assertThat(one.scope).isNotSameInstanceAs(other.scope)
        assertThat(started).containsExactly(one.scope, other.scope).inOrder()
    }

    @Test
    fun `closing one leaves the other running, and a profile still listed keeps its folders`() = runTest {
        val registry = twoListed()
        val factory = factory(registry)
        val first = factory.opened(profile("p0", userId = 1))
        val second = factory.opened(profile("p1", userId = 2))

        factory.close(first, backgroundScope)

        assertThat(closed).containsExactly("p0")
        assertThat(first.scope.isActive).isFalse()
        assertThat(second.scope.isActive).isTrue()
        assertThat(foldersExist("p0")).isTrue()

        // Opened again, in the same folders, with objects of its own.
        val again = factory.opened(profile("p0", userId = 1))
        assertThat((again.component as Built).folders).isEqualTo(roots.of("p0"))
        assertThat(again.scope.isActive).isTrue()
    }

    /**
     * A job of a profile that fails (a download reading a store on its way out) is reported and ends alone: it
     * neither ends the profile's other jobs nor goes uncaught, which ends the app on a device.
     */
    @Test
    fun `a job of a profile that fails is reported, and ends neither the others nor the app`() = runTest {
        val registry = twoListed()
        val warned = mutableListOf<Throwable?>()
        val failing = IllegalStateException("a job failed")
        val factory = ProfileGraphFactory(
            registry,
            roots,
            avatars,
            GraphMaking(
                build = ::Built,
                lifeOf = {
                    object : ProfileLifeEntry {
                        override fun starters() = setOf(ProfileStarter { scope -> scope.launch { throw failing } })

                        override fun closers() = emptySet<ProfileCloser>()
                    }
                },
                warn = { _, failure -> warned += failure },
                graph = UnconfinedTestDispatcher(testScheduler),
                io = UnconfinedTestDispatcher(testScheduler),
            ),
            registry,
        )

        val graph = factory.opened(profile("p0", userId = 1))

        assertThat(warned).containsExactly(failing)
        assertThat(graph.scope.isActive).isTrue()
    }
}
