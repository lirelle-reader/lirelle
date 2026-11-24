package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.clearCurrent
import app.tfdn.lirelle.core.session.remove
import app.tfdn.lirelle.core.session.renew
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Closing a profile when something does not go well: a closer or a coroutine that does not end, folders that
 * are long to delete, or that cannot be. Whatever happens, the next profile opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphsClosingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }

    private val events = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val built = mutableListOf<Built>()
    private var closers: Set<ProfileCloser> = emptySet()

    /** Lets go, at the end of a test, of what was made never to end. */
    private val release = CompletableDeferred<Unit>()

    private class Built(val session: ProfileSession, val scope: CoroutineScope) : ProfileComponent

    private val life = object : ProfileLifeEntry {
        override fun starters() = emptySet<ProfileStarter>()

        override fun closers() = closers
    }

    private fun TestScope.graphs(
        registry: FakeRegistry,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) = ProfileGraphs(
        registry,
        registry,
        roots,
        avatars,
        GraphMaking(
            build = { session, _, scope -> Built(session, scope).also { built += it } },
            lifeOf = { life },
            warn = { message, _ -> warnings += message },
            graph = UnconfinedTestDispatcher(testScheduler),
            io = io,
        ),
        registry,
    )

    private fun foldersExist(id: String) = roots.of(id).let { it.files.exists() || it.cache.exists() }

    /** Makes the folders of [id] impossible to delete, until the returned function is called. */
    private fun stick(id: String): () -> Unit {
        val files = roots.of(id).files.apply { mkdirs() }
        File(files, "kept").writeText("kept")
        // Whoever runs the tests as root deletes it all the same: nothing can be told then.
        assumeTrue(files.setWritable(false) && !files.canWrite())
        return { files.setWritable(true) }
    }

    @Test
    fun `a closer that takes its time is waited for`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        closers = setOf(
            ProfileCloser {
                delay(1.seconds)
                events += "closed: folders ${foldersExist("p7")}"
            },
        )
        graphs(registry).open(backgroundScope)

        registry.clearCurrent(SignOutReason.UserRequest)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertThat(events).containsExactly("closed: folders true")
        assertThat(warnings).isEmpty()
        assertThat(foldersExist("p7")).isFalse()
    }

    @Test
    fun `a closer that never ends is given up, the others run and the profile is removed`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        closers = linkedSetOf(
            ProfileCloser { awaitCancellation() },
            // One that does not even end when it is told to.
            ProfileCloser { withContext(NonCancellable) { release.await() } },
            ProfileCloser { events += "closed" },
        )
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        registry.clearCurrent(SignOutReason.UserRequest)
        assertThat(foldersExist("p7")).isTrue()
        advanceTimeBy(1.minutes)
        registry.save(session(userId = 2))

        assertThat(events).containsExactly("closed")
        assertThat(warnings).hasSize(2)
        assertThat(foldersExist("p7")).isFalse()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(graphs.current.value?.profileId).isEqualTo("p0")
        release.complete(Unit)
    }

    @Test
    fun `what a profile was doing and does not end is not waited for ever`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        built.single().scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }

        registry.clearCurrent(SignOutReason.UserRequest)
        assertThat(foldersExist("p7")).isTrue()
        advanceTimeBy(1.minutes)
        registry.save(session(userId = 2))

        assertThat(warnings).hasSize(1)
        assertThat(foldersExist("p7")).isFalse()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(graphs.current.value?.profileId).isEqualTo("p0")
        release.complete(Unit)
    }

    @Test
    fun `the next profile opens while the folders of the one before are still being deleted`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry, io = StandardTestDispatcher(testScheduler))
        graphs.open(backgroundScope)
        runCurrent()

        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(session(userId = 2))

        assertThat(graphs.current.value?.profileId).isEqualTo("p0")
        assertThat(foldersExist("p7")).isTrue()

        runCurrent()

        assertThat(foldersExist("p7")).isFalse()
        assertThat(foldersExist("p0")).isTrue()
        assertThat(registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `a profile whose folders cannot be deleted stays to be removed, and the next one opens`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val unstick = stick("p7")

        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(session(userId = 2))
        unstick()

        // Still listed as being removed: the next start tries again.
        assertThat(registry.beingRemoved()).containsExactly("p7")
        assertThat(warnings).hasSize(1)
        assertThat(foldersExist("p7")).isTrue()
        assertThat(graphs.current.value?.profileId).isEqualTo("p0")
    }

    @Test
    fun `a sleeping profile whose folders cannot be deleted stays to be removed, and nothing is thrown`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7"), alsoListed = listOf(profile("p8", userId = 2)))
        val graphs = graphs(registry)
        graphs.open(backgroundScope)
        val unstick = stick("p8")

        registry.remove("p8", SignOutReason.UserRequest)
        // Whatever the store tells next, it is not tried again before the next start: said once.
        registry.renew("p7", credential(access = "access-renewed"))
        unstick()

        assertThat(registry.beingRemoved()).containsExactly("p8")
        assertThat(warnings)
            .containsExactly("The folders of a profile could not all be deleted: left for the next start")
        assertThat(foldersExist("p8")).isTrue()
        assertThat(graphs.current.value?.component).isSameInstanceAs(built.single())
        assertThat(built.single().scope.isActive).isTrue()
    }

    @Test
    fun `at start, a record that cannot be dropped is left for the next start and the others go`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(leftBeingRemoved = listOf("full", "gone"))
        // The registry's file cannot be written: no room left on the device.
        registry.onForget = { if (it == "full") throw IOException("No space left on device") }
        roots.of("full").cache.mkdirs()
        roots.of("gone").cache.mkdirs()

        graphs(registry).open(backgroundScope)

        assertThat(registry.beingRemoved()).containsExactly("full")
        assertThat(foldersExist("gone")).isFalse()
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `at start, folders that cannot be deleted do not keep the others from going`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(leftBeingRemoved = listOf("stuck", "gone"))
        roots.of("gone").cache.mkdirs()
        val unstick = stick("stuck")

        graphs(registry).open(backgroundScope)
        unstick()

        assertThat(registry.beingRemoved()).containsExactly("stuck")
        assertThat(foldersExist("stuck")).isTrue()
        assertThat(foldersExist("gone")).isFalse()
    }
}
