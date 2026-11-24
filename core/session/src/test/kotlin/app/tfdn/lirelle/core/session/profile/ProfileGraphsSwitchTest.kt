package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.clearCurrent
import app.tfdn.lirelle.core.session.remove
import app.tfdn.lirelle.core.session.renew
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Going from one profile to another among several on the device: what the screens are told, what is closed
 * and opened, and what becomes of a change during which the profiles themselves change — another switch, a
 * removal. It always ends: on a profile, or signed out, never on the wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphsSwitchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }

    /** What happened, in order. */
    private val events = mutableListOf<String>()
    private val built = mutableListOf<Built>()
    private var started = 0
    private var closers: Set<ProfileCloser> = emptySet()

    /** The objects of a profile, as the test's `build` makes them: what they were built with. */
    private class Built(val session: ProfileSession, val scope: CoroutineScope) : ProfileComponent

    private val life = object : ProfileLifeEntry {
        override fun starters() = setOf(ProfileStarter { started++ })

        override fun closers() = closers
    }

    private fun TestScope.graphs(
        registry: FakeRegistry,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): ProfileGraphs {
        lateinit var graphs: ProfileGraphs
        val making = GraphMaking(
            build = { session, _, scope ->
                events += "built ${session.profileId} while ${graphs.shown.value.state.name()}"
                Built(session, scope).also { built += it }
            },
            lifeOf = { life },
            warn = { _, _ -> },
            graph = UnconfinedTestDispatcher(testScheduler),
            io = io,
        )
        graphs = ProfileGraphs(registry, registry, roots, avatars, making, registry)
        return graphs
    }

    private fun SessionState.name() = this::class.simpleName

    /** What the screens are told, in a few words: the wait of a change of profile is not any other wait. */
    private fun ShownSession.told() = if (switching) "switching" else "${state.name()} $profileId"

    private fun foldersExist(id: String) = roots.of(id).let { it.files.exists() || it.cache.exists() }

    /** Three profiles on the device: p7 is the current one, p8 and p9 sleep. */
    private fun three() = FakeRegistry(
        stored = profile("p7"),
        alsoListed = listOf(profile("p8", userId = 2), profile("p9", userId = 3)),
    )

    /** What the screens are told from now on, in order. */
    private fun TestScope.told(graphs: ProfileGraphs): List<String> {
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { graphs.shown.collect { seen += it.told() } }
        return seen
    }

    @Test
    fun `a switch shows the profile left, the wait, then the other profile, and never signed out`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = three()
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val seen = told(graphs)

        registry.switchTo("p8")

        assertThat(seen).containsExactly("SignedIn p7", "switching", "SignedIn p8").inOrder()
        assertThat(graphs.shown.value).isEqualTo(ShownSession(SessionState.SignedIn(session(userId = 2)), "p8"))
        assertThat(graphs.current.value?.profileId).isEqualTo("p8")
        // The profile left is kept whole: it sleeps.
        assertThat(registry.profile("p7")).isNotNull()
        assertThat(roots.of("p7").files.isDirectory).isTrue()
    }

    @Test
    fun `a switch runs the closers of the profile left and ends its scope before the other is built`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = three()
        val graphs = graphs(registry)
        closers = setOf(
            ProfileCloser {
                events += "closer: ${graphs.shown.value.told()}, scope active ${built.first().scope.isActive}"
            },
        )
        graphs.open(backgroundScope)
        built.single().scope.launch { awaitCancellation() }.invokeOnCompletion { events += "scope of p7 ended" }
        events.clear()

        registry.switchTo("p8")

        assertThat(events)
            .containsExactly("closer: switching, scope active true", "scope of p7 ended", "built p8 while Loading")
            .inOrder()
        assertThat(built.first().scope.isActive).isFalse()
        assertThat(built.last().scope.isActive).isTrue()
    }

    @Test
    fun `two switches, the second once the first has ended, open each profile in turn and close the one left`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = three()
            val graphs = graphs(registry)
            graphs.open(backgroundScope)

            registry.switchTo("p8")
            registry.switchTo("p9")

            assertThat(graphs.shown.value).isEqualTo(ShownSession(SessionState.SignedIn(session(userId = 3)), "p9"))
            assertThat(graphs.current.value?.component).isSameInstanceAs(built.last())
            assertThat(built.map { it.session.profileId to it.scope.isActive })
                .containsExactly("p7" to false, "p8" to false, "p9" to true).inOrder()
            assertThat(roots.present()).containsExactly("p7", "p8", "p9")
        }

    @Test
    fun `a switch asked during another waits for it, and a profile current for an instant is never opened`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = three()
            val graphs = graphs(registry)
            closers = setOf(ProfileCloser { delay(1.seconds) })
            graphs.open(backgroundScope)
            val seen = told(graphs)

            // The profile left is still closing when the switches that follow are asked.
            registry.switchTo("p8")
            registry.switchTo("p9")
            registry.switchTo("p8")
            registry.switchTo("p9")
            assertThat(graphs.shown.value.switching).isTrue()
            assertThat(built).hasSize(1)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(built.map { it.session.profileId }).containsExactly("p7", "p9").inOrder()
            assertThat(seen).containsExactly("SignedIn p7", "switching", "SignedIn p9").inOrder()
            assertThat(graphs.current.value?.profileId).isEqualTo("p9")
        }

    @Test
    fun `a switch during which every profile is removed ends signed out, not on the wait`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = three()
            val graphs = graphs(registry)
            closers = setOf(ProfileCloser { delay(1.seconds) })
            graphs.open(backgroundScope)
            val seen = told(graphs)

            registry.switchTo("p8")
            assertThat(graphs.shown.value.switching).isTrue()
            // The profile left is still closing when all three go.
            registry.remove("p8", SignOutReason.UserRequest)
            registry.remove("p9", SignOutReason.UserRequest)
            registry.remove("p7", SignOutReason.UserRequest)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(seen).containsExactly("SignedIn p7", "switching", "SignedOut null").inOrder()
            assertThat(graphs.shown.value.switching).isFalse()
            assertThat(graphs.current.value).isNull()
            assertThat(built.map { it.session.profileId }).containsExactly("p7")
            assertThat(roots.present()).isEmpty()
            assertThat(registry.listedIds()).isEmpty()
        }

    @Test
    fun `a switch to a profile removed meanwhile, the one left being the last, comes back to it`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))
            val graphs = graphs(registry)
            closers = setOf(ProfileCloser { delay(1.seconds) })
            graphs.open(backgroundScope)
            val seen = told(graphs)

            registry.switchTo("p8")
            // The profile left is still closing when the one asked for goes.
            registry.remove("p8", SignOutReason.UserRequest)
            advanceTimeBy(1.seconds)
            runCurrent()

            assertThat(seen).containsExactly("SignedIn p7", "switching", "SignedIn p7").inOrder()
            assertThat(graphs.current.value?.profileId).isEqualTo("p7")
            assertThat(built.map { it.session.profileId to it.scope.isActive })
                .containsExactly("p7" to false, "p7" to true).inOrder()
            assertThat(roots.present()).containsExactly("p7")
        }

    @Test
    fun `going from a profile to no profile shows signed out with the server's address, not the wait`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val seen = told(graphs)

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(seen).containsExactly("SignedIn p7", "SignedOut null").inOrder()
        assertThat(graphs.shown.value)
            .isEqualTo(ShownSession(SessionState.SignedOut(Server, SignOutReason.UserRequest), profileId = null))
    }

    @Test
    fun `removing the current profile while another is left shows the wait, then that one, and deletes its folders`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = three()
            val graphs = graphs(registry)
            graphs.open(backgroundScope)
            val seen = told(graphs)

            registry.clearCurrent(SignOutReason.UserRequest)

            assertThat(seen).containsExactly("SignedIn p7", "switching", "SignedIn p8").inOrder()
            assertThat(foldersExist("p7")).isFalse()
            assertThat(registry.listedIds()).containsExactly("p8", "p9")
        }

    @Test
    fun `a sleeping profile removed loses its folders and its record at once, and the open one is untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = three()
            roots.of("p8").files.resolve("datastore").mkdirs()
            roots.of("p8").cache.mkdirs()
            val graphs = graphs(registry, io = StandardTestDispatcher(testScheduler))
            graphs.open(backgroundScope)
            runCurrent()
            val open = graphs.current.value
            val seen = told(graphs)

            registry.remove("p8", SignOutReason.UserRequest)

            // In the background: who removed it does not wait for folders of downloads to go.
            assertThat(foldersExist("p8")).isTrue()
            assertThat(registry.beingRemoved()).containsExactly("p8")

            runCurrent()

            assertThat(foldersExist("p8")).isFalse()
            assertThat(registry.listedIds()).containsExactly("p7", "p9")
            assertThat(graphs.current.value).isSameInstanceAs(open)
            assertThat(built).hasSize(1)
            assertThat(started).isEqualTo(1)
            assertThat(built.single().scope.isActive).isTrue()
            assertThat(roots.of("p7").files.isDirectory).isTrue()
            assertThat(seen).containsExactly("SignedIn p7")
            // Still usable: its session takes a renewal.
            val renewed = credential(access = "access-renewed")
            registry.renew("p7", renewed)
            assertThat(built.single().session.session.value.credential).isEqualTo(renewed)
        }
}
