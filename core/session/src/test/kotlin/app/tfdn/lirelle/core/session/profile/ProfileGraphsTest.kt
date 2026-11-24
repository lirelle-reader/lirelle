package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.clearCurrent
import app.tfdn.lirelle.core.session.renew
import app.tfdn.lirelle.core.session.setServerUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }

    /** What happened, in order. */
    private val events = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val built = mutableListOf<Built>()
    private var started = 0
    private var starter = ProfileStarter { started++ }
    private var closers: Set<ProfileCloser> = emptySet()

    /** The objects of a profile, as the test's `build` makes them: what they were built with. */
    private class Built(val session: ProfileSession, val folders: ProfileFolders, val scope: CoroutineScope) :
        ProfileComponent

    private val life = object : ProfileLifeEntry {
        override fun starters() = setOf(starter)

        override fun closers() = closers
    }

    private fun TestScope.graphs(
        registry: FakeRegistry,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        graph: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): ProfileGraphs {
        lateinit var graphs: ProfileGraphs
        val making = GraphMaking(
            build = { session, folders, scope ->
                events += "built ${session.profileId} while ${graphs.shown.value.state.name()}"
                Built(session, folders, scope).also { built += it }
            },
            lifeOf = { life },
            warn = { message, failure -> warnings += "$message (${failure?.message})" },
            graph = graph,
            io = io,
        )
        graphs = ProfileGraphs(registry, registry, roots, avatars, making, registry)
        return graphs
    }

    private fun SessionState.name() = this::class.simpleName

    /** What the screens are told, in a few words: the wait of a change of profile is not any other wait. */
    private fun ShownSession.told() = if (switching) "switching" else "${state.name()} $profileId"

    private fun foldersExist(id: String) = roots.of(id).let { it.files.exists() || it.cache.exists() }

    @Test
    fun `opening with a current profile builds its objects in its folders and starts them once`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val graphs = graphs(FakeRegistry(stored = profile("p7")))

        graphs.open(backgroundScope)

        assertThat(built).hasSize(1)
        assertThat(built.single().session.profileId).isEqualTo("p7")
        assertThat(built.single().folders).isEqualTo(roots.of("p7"))
        assertThat(roots.of("p7").files.isDirectory).isTrue()
        assertThat(roots.of("p7").cache.isDirectory).isTrue()
        assertThat(started).isEqualTo(1)
        assertThat(graphs.current.value?.profileId).isEqualTo("p7")
        assertThat(graphs.current.value?.component).isSameInstanceAs(built.single())
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedIn(session()))
    }

    @Test
    fun `opening with no profile builds nothing and says signed out`() = runTest(UnconfinedTestDispatcher()) {
        val graphs = graphs(FakeRegistry())
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.Loading)

        graphs.open(backgroundScope)

        assertThat(built).isEmpty()
        assertThat(graphs.current.value).isNull()
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
    }

    @Test
    fun `a server remembered while signed out is told to the screens`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry()
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        registry.setServerUrl(Server)

        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(built).isEmpty()
    }

    @Test
    fun `signing in builds the profile's objects before the screens are told`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry()
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        registry.save(session())

        assertThat(events).containsExactly("built p0 while SignedOut")
        assertThat(started).isEqualTo(1)
        assertThat(graphs.current.value?.profileId).isEqualTo("p0")
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedIn(session()))
    }

    @Test
    fun `renewed tokens are told to the screens and to the profile, whose objects stay`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val renewed = credential(access = "access-renewed")

        registry.renew("p7", renewed)

        assertThat(built).hasSize(1)
        assertThat(started).isEqualTo(1)
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedIn(session().copy(credential = renewed)))
        assertThat(built.single().session.session.value.credential).isEqualTo(renewed)
    }

    @Test
    fun `a session that expires, then its account signing in again, leave the profile open in the same objects`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = FakeRegistry(stored = profile("p7"))
            val graphs = graphs(registry)
            graphs.open(backgroundScope)
            val seen = mutableListOf<String>()
            backgroundScope.launch { graphs.shown.collect { seen += it.told() } }
            val own = built.single().session

            own.expire(own.session.value.credential)

            assertThat(own.signInNeeded.value).isTrue()
            assertThat(graphs.current.value?.component).isSameInstanceAs(built.single())
            assertThat(foldersExist("p7")).isTrue()

            val again = session().copy(credential = credential(access = "access-again"))
            registry.save(again)

            assertThat(own.signInNeeded.value).isFalse()
            assertThat(own.session.value).isEqualTo(again)
            assertThat(built).hasSize(1)
            assertThat(started).isEqualTo(1)
            assertThat(built.single().scope.isActive).isTrue()
            // Never the wait, never signed out: the screens stay where they are.
            assertThat(seen).containsExactly("SignedIn p7", "SignedIn p7").inOrder()
        }

    @Test
    fun `signing out tells the screens, then closes the objects, deletes the folders, forgets the profile`() =
        runTest(UnconfinedTestDispatcher()) {
            val registry = FakeRegistry(stored = profile("p7"))
            val graphs = graphs(registry)
            closers = setOf(
                ProfileCloser {
                    val scope = built.single().scope
                    events += "closer: ${graphs.shown.value.state.name()}, current ${graphs.current.value}, " +
                        "scope active ${scope.isActive}, folders ${foldersExist("p7")}"
                },
            )
            registry.onForget = { events += "forgotten $it: folders ${foldersExist(it)}" }
            graphs.open(backgroundScope)
            built.single().scope.launch { awaitCancellation() }
                .invokeOnCompletion { events += "scope ended: folders ${foldersExist("p7")}" }
            events.clear()

            registry.clearCurrent(SignOutReason.UserRequest)

            assertThat(events).containsExactly(
                "closer: SignedOut, current null, scope active true, folders true",
                "scope ended: folders true",
                "forgotten p7: folders false",
            ).inOrder()
            assertThat(built.single().scope.isActive).isFalse()
            assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
            assertThat(registry.beingRemoved()).isEmpty()
        }

    @Test
    fun `a profile just closed is kept for the screens still leaving it, until the next one is open`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry()
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        // What somebody finds who looks for the open profile first, then for the closed one.
        val found = mutableListOf<String>()
        backgroundScope.launch {
            graphs.current.collect { found += "open ${it?.profileId}, closed ${graphs.lastClosed?.profileId}" }
        }
        assertThat(graphs.lastClosed).isNull()

        registry.save(session(userId = 1))
        val first = graphs.current.value
        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(graphs.lastClosed).isSameInstanceAs(first)
        assertThat(built.single().scope.isActive).isFalse()

        registry.save(session(userId = 2))

        assertThat(graphs.lastClosed).isNull()
        assertThat(found).containsExactly(
            "open null, closed null",
            "open p0, closed null",
            "open null, closed p0",
            "open p1, closed null",
        ).inOrder()
    }

    @Test
    fun `a closer that fails stops neither the others nor the removal`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        closers = linkedSetOf(
            ProfileCloser { error("cannot close") },
            ProfileCloser { events += "closed" },
        )
        graphs.open(backgroundScope)
        events.clear()

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(events).containsExactly("closed")
        assertThat(warnings).containsExactly("A closer of a profile failed (cannot close)")
        assertThat(foldersExist("p7")).isFalse()
        assertThat(registry.listedIds()).isEmpty()
    }

    @Test
    fun `the screens are never told signed in while that profile's objects are not the open ones`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry()
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val seen = mutableListOf<String>()
        backgroundScope.launch { graphs.shown.collect { seen += "shown ${it.state.name()} ${it.profileId}" } }
        backgroundScope.launch { graphs.current.collect { seen += "open ${it?.profileId}" } }

        registry.save(session(userId = 1))
        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(session(userId = 2))

        assertThat(seen).containsExactly(
            "shown SignedOut null",
            "open null",
            "open p0",
            "shown SignedIn p0",
            "shown SignedOut null",
            "open null",
            "open p1",
            "shown SignedIn p1",
        ).inOrder()
        assertThat(built.map { it.session.profileId }).containsExactly("p0", "p1").inOrder()
        assertThat(foldersExist("p0")).isFalse()
        assertThat(foldersExist("p1")).isTrue()
    }

    /**
     * Signs in, out, and in again with [second] while what follows the store is busy: it sees the first
     * profile, then the second, and nothing of what was in between.
     */
    private suspend fun TestScope.jump(registry: FakeRegistry, graphs: ProfileGraphs, second: Session): List<String> {
        graphs.open(backgroundScope)
        registry.save(session(userId = 1))
        runCurrent()
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            graphs.shown.collect { seen += "${it.told()}, open ${graphs.current.value?.profileId}" }
        }

        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(second)
        runCurrent()
        return seen
    }

    @Test
    fun `a jump from one profile to another shows the wait, closes the first, opens the second`() =
        runTest {
            val registry = FakeRegistry()
            val graphs = graphs(registry)

            val seen = jump(registry, graphs, second = session(userId = 2))

            assertThat(seen).containsExactly("SignedIn p0, open p0", "switching, open p0", "SignedIn p1, open p1")
                .inOrder()
            assertThat(built.map { it.session.profileId }).containsExactly("p0", "p1").inOrder()
            assertThat(built.first().scope.isActive).isFalse()
            assertThat(foldersExist("p0")).isFalse()
            assertThat(foldersExist("p1")).isTrue()
            assertThat(registry.beingRemoved()).isEmpty()
        }

    @Test
    fun `a jump to another profile is seen even when its session is equal to the one before`() = runTest {
        val registry = FakeRegistry()
        val graphs = graphs(registry)

        // The same account signs in again: an equal session, in a new profile.
        val seen = jump(registry, graphs, second = session(userId = 1))

        assertThat(seen).containsExactly("SignedIn p0, open p0", "switching, open p0", "SignedIn p1, open p1")
            .inOrder()
        assertThat(built.map { it.session.profileId }).containsExactly("p0", "p1").inOrder()
        assertThat(foldersExist("p0")).isFalse()
        assertThat(foldersExist("p1")).isTrue()
    }

    @Test
    fun `what starts with a profile starts before anybody is told that the profile is open`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry()
        val graphs = graphs(registry)
        starter = ProfileStarter {
            val shown = graphs.shown.value
            events += "started: shown ${shown.state.name()} ${shown.profileId}, open ${graphs.current.value?.profileId}"
        }
        graphs.open(backgroundScope)

        registry.save(session())

        assertThat(events)
            .containsExactly("built p0 while SignedOut", "started: shown SignedOut null, open null")
            .inOrder()
        assertThat(graphs.shown.value.profileId).isEqualTo("p0")
    }

    @Test
    fun `the folders go only once what the profile was doing has ended`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry, graph = StandardTestDispatcher(testScheduler))
        graphs.open(backgroundScope)
        built.single().scope.launch {
            try {
                awaitCancellation()
            } finally {
                // What a profile object does as it ends, and that takes its time: a last write.
                withContext(NonCancellable) {
                    yield()
                    events += "ended: folders ${foldersExist("p7")}"
                }
            }
        }
        runCurrent()
        events.clear()

        registry.clearCurrent(SignOutReason.UserRequest)
        assertThat(foldersExist("p7")).isTrue()
        runCurrent()

        assertThat(events).containsExactly("ended: folders true")
        assertThat(foldersExist("p7")).isFalse()
    }

    @Test
    fun `a profile that was only closed keeps its folders`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        // The store shows nobody, and still has the profile as ready: closed, not removed.
        registry.current.value = null
        registry.state.value = SessionState.SignedOut(Server, reason = null)

        assertThat(graphs.current.value).isNull()
        assertThat(built.single().scope.isActive).isFalse()
        assertThat(registry.profile("p7")).isNotNull()
        assertThat(roots.of("p7").files.isDirectory).isTrue()
        assertThat(roots.of("p7").cache.isDirectory).isTrue()
    }

    @Test
    fun `a session said signed in while the store has no current profile changes nothing`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        // The store has gone further than the session that is told: its profile is already gone from it.
        registry.current.value = null
        registry.state.value = SessionState.SignedIn(session().copy(credential = credential().copy(sessionId = 2)))

        assertThat(graphs.current.value?.profileId).isEqualTo("p7")
        assertThat(built.single().scope.isActive).isTrue()
        assertThat(graphs.shown.value).isEqualTo(ShownSession(SessionState.SignedIn(session()), "p7"))
    }

    @Test
    fun `opening finishes in the background what an interruption left, and does not wait for it`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"), leftBeingRemoved = listOf("gone"))
        // A profile whose removal was interrupted, and a folder that no profile owns.
        roots.of("gone").files.mkdirs()
        roots.of("stray").cache.mkdirs()
        val graphs = graphs(registry, io = StandardTestDispatcher(testScheduler))

        graphs.open(backgroundScope)

        assertThat(graphs.current.value?.profileId).isEqualTo("p7")
        assertThat(foldersExist("gone")).isTrue()
        assertThat(foldersExist("stray")).isTrue()

        runCurrent()

        assertThat(foldersExist("gone")).isFalse()
        assertThat(foldersExist("stray")).isFalse()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(roots.present()).containsExactly("p7")
    }

    @Test
    fun `a profile removed as it starts is not taken for one that was left behind`() = runTest {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        starter = ProfileStarter { scope -> scope.launch { registry.clearCurrent(SignOutReason.UserRequest) } }

        graphs.open(backgroundScope)

        // What follows the store has not run yet: the profile is still open, over its folders.
        assertThat(registry.beingRemoved()).containsExactly("p7")
        assertThat(graphs.current.value?.profileId).isEqualTo("p7")
        assertThat(foldersExist("p7")).isTrue()

        runCurrent()

        assertThat(graphs.current.value).isNull()
        assertThat(foldersExist("p7")).isFalse()
        assertThat(registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `opening a second time does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        var sweeps = 0
        registry.onListedIds = { sweeps++ }
        val graphs = graphs(registry)

        graphs.open(backgroundScope)
        graphs.open(backgroundScope)

        assertThat(sweeps).isEqualTo(1)
        assertThat(built).hasSize(1)
        assertThat(started).isEqualTo(1)
    }

    @Test
    fun `a registry that could not be read costs no profile its folders`() = runTest(UnconfinedTestDispatcher()) {
        // The file lists these profiles still; it could only not be read at this start.
        roots.of("p7").files.mkdirs()
        roots.of("p8").cache.mkdirs()
        val graphs = graphs(FakeRegistry(unreadable = true))

        graphs.open(backgroundScope)

        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
        assertThat(roots.present()).containsExactly("p7", "p8")
    }

    @Test
    fun `what was set aside in the cache goes in the background, with what is nobody's`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val aside = roots.aside("old-audio").apply { resolve("0").mkdirs() }
        aside.resolve("0/stream.exo").writeText("audio")
        roots.of("p7").cache.mkdirs()
        val graphs = graphs(FakeRegistry(stored = profile("p7")), io = StandardTestDispatcher(testScheduler))

        graphs.open(backgroundScope)

        // The start did not wait for it.
        assertThat(aside.resolve("0/stream.exo").exists()).isTrue()

        runCurrent()

        assertThat(aside.exists()).isFalse()
        assertThat(roots.present()).containsExactly("p7")
    }

    @Test
    fun `what was set aside is left for the next start when the registry could not be read`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val aside = roots.aside("old-audio").apply { mkdirs() }

        graphs(FakeRegistry(unreadable = true)).open(backgroundScope)

        assertThat(aside.exists()).isTrue()
    }

    @Test
    fun `a profile still being moved in is not opened, and the screens say the update is unfinished`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(leftBeingMoved = listOf("half"))
        val graphs = graphs(registry)

        graphs.open(backgroundScope)
        // Nothing the store says afterwards is followed: neither a sign-in screen nor a profile beside it.
        registry.setServerUrl(Server)
        registry.save(session())

        assertThat(built).isEmpty()
        assertThat(graphs.current.value).isNull()
        assertThat(graphs.shown.value)
            .isEqualTo(ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true))
    }

    @Test
    fun `halted, nothing is opened and the screens say the update is unfinished`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)

        graphs.halt()

        assertThat(built).isEmpty()
        assertThat(graphs.current.value).isNull()
        assertThat(graphs.shown.value)
            .isEqualTo(ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true))
        // The store was not even read: what it lists is left for the start that can finish the update.
        assertThat(registry.state.value).isEqualTo(SessionState.Loading)
    }

    @Test
    fun `the folders of a profile still being moved in are kept, and what is nobody's goes all the same`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        roots.of("half").files.mkdirs()
        roots.of("half").cache.mkdirs()
        roots.of("stray").cache.mkdirs()
        val graphs = graphs(FakeRegistry(leftBeingMoved = listOf("half"), leftBeingRemoved = listOf("gone")))
        roots.of("gone").files.mkdirs()

        graphs.open(backgroundScope)

        assertThat(roots.present()).containsExactly("half")
        assertThat(roots.of("half").files.isDirectory).isTrue()
        assertThat(roots.of("half").cache.isDirectory).isTrue()
    }

    @Test
    fun `a profile saved while opening looks for what was left keeps its folders`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry()
        roots.of("stray").cache.mkdirs()
        // Signed in from another thread just after the ids were read: its folder is made, its id not seen.
        registry.onListedIds = { roots.of("late").files.mkdirs() }
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(roots.present()).containsExactly("late")
    }
}
