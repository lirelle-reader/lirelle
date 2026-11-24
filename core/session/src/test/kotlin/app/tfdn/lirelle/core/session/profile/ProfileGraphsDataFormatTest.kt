package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.OldDataFormat
import app.tfdn.lirelle.core.session.SessionState
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A profile whose folders hold data of the format before the app named things by ids of its own starts again
 * from empty folders, once, when it is opened — at the start, or at a switch — and before its objects are built:
 * nothing of it is open while its folders are set aside.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileGraphsDataFormatTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val filesRoot by lazy { folder.newFolder("files") }
    private val roots by lazy { ProfileRoots(filesRoot, folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }

    /** What each profile's folders held when its objects were built, by profile. */
    private val heldWhenBuilt = mutableMapOf<String, List<String>>()
    private val warnings = mutableListOf<String>()
    private var unlock: () -> Unit = {}

    private class Built(val session: ProfileSession, val scope: CoroutineScope) : ProfileComponent

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
            build = { session, folders, scope ->
                heldWhenBuilt[session.profileId] = held(folders)
                Built(session, scope)
            },
            lifeOf = { life },
            warn = { message, _ -> warnings += message },
            graph = UnconfinedTestDispatcher(testScheduler),
            io = UnconfinedTestDispatcher(testScheduler),
        ),
        registry,
    )

    @After
    fun unlockFolders() = unlock()

    private fun held(folders: ProfileFolders): List<String> = listOf(folders.files, folders.cache)
        .flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList() }

    /** Data in both folders of [id], in stores and files, as a profile that was used leaves them. */
    private fun dataIn(id: String) {
        val folders = roots.of(id)
        File(folders.files, "datastore/reading.preferences_pb").apply { parentFile?.mkdirs() }.writeText("positions")
        File(folders.files, "downloads/41/audiobook/a1").apply { parentFile?.mkdirs() }.writeText("audio")
        File(folders.cache, "covers/41").apply { parentFile?.mkdirs() }.writeText("cover")
    }

    /** The names under the profiles' roots, either of them: the profiles' folders, and what was set aside. */
    private fun present(): Set<String> = roots.present()

    @Test
    fun `a profile of the old format opens on empty folders, and is of the current format from then on`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7", dataFormat = OldDataFormat))
        dataIn("p7")
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(heldWhenBuilt).containsExactly("p7", emptyList<String>())
        assertThat(graphs.current.value?.profileId).isEqualTo("p7")
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedIn(session()))
        assertThat(registry.profile("p7")?.dataFormat).isEqualTo(CurrentDataFormat)
        // What was set aside is gone, in the background: only the profile's own, empty, folders are left.
        assertThat(present()).containsExactly("p7")
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `a profile already of the current format is untouched`() = runTest(UnconfinedTestDispatcher()) {
        val registry = FakeRegistry(stored = profile("p7"))
        dataIn("p7")

        graphs(registry).open(backgroundScope)

        assertThat(heldWhenBuilt["p7"]).containsExactly(
            "datastore/reading.preferences_pb",
            "downloads/41/audiobook/a1",
            "covers/41",
        )
        assertThat(registry.profile("p7")?.dataFormat).isEqualTo(CurrentDataFormat)
    }

    @Test
    fun `a start interrupted once the folders were set aside ends the same at the next`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // The folders were renamed aside, and the format not written yet: what an interruption leaves.
        val registry = FakeRegistry(stored = profile("p7", dataFormat = OldDataFormat))
        File(roots.asideOfFiles("old-data-p7"), "downloads/41").mkdirs()
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(heldWhenBuilt).containsExactly("p7", emptyList<String>())
        assertThat(registry.profile("p7")?.dataFormat).isEqualTo(CurrentDataFormat)
        // What the interrupted start set aside is nobody's, and the start's sweep deleted it.
        assertThat(present()).containsExactly("p7")
    }

    @Test
    fun `a switch to a sleeping profile of the old format empties it before it opens, and only it`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(
            stored = profile("p7"),
            alsoListed = listOf(profile("p8", userId = 2, dataFormat = OldDataFormat)),
        )
        dataIn("p7")
        dataIn("p8")
        val graphs = graphs(registry)
        graphs.open(backgroundScope)

        registry.switchTo("p8")

        assertThat(graphs.current.value?.profileId).isEqualTo("p8")
        assertThat(heldWhenBuilt["p8"]).isEmpty()
        assertThat(registry.profile("p8")?.dataFormat).isEqualTo(CurrentDataFormat)
        assertThat(held(roots.of("p7"))).hasSize(3)
        assertThat(present()).containsExactly("p7", "p8")
    }

    @Test
    fun `a profile whose folders cannot be set aside is not opened, and the one used before it is`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(
            stored = profile("p7", dataFormat = OldDataFormat),
            alsoListed = listOf(profile("p8", userId = 2)),
        )
        dataIn("p7")
        dataIn("p8")
        lockProfilesRoot()
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(heldWhenBuilt.keys).containsExactly("p8")
        assertThat(graphs.current.value?.profileId).isEqualTo("p8")
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedIn(session(userId = 2)))
        // Nothing of it was touched: its format stays the old one, for the next start to try again.
        assertThat(registry.withheld.map { it.id to it.dataFormat }).containsExactly("p7" to OldDataFormat)
        assertThat(held(roots.of("p7"))).hasSize(3)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("could not be set aside")
    }

    @Test
    fun `a format that cannot be written withholds the profile, and the log says it was that step`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(
            stored = profile("p7", dataFormat = OldDataFormat),
            alsoListed = listOf(profile("p8", userId = 2)),
        )
        registry.failFormatWrite = true
        dataIn("p7")
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(graphs.current.value?.profileId).isEqualTo("p8")
        assertThat(registry.withheld.map { it.id to it.dataFormat }).containsExactly("p7" to OldDataFormat)
        assertThat(warnings.single()).contains("format could not be written")
        // What was set aside is nobody's: deleted all the same, the next start finds the folders empty.
        assertThat(present().filter { it.startsWith("old-data-") }).isEmpty()
        assertThat(held(roots.of("p7"))).isEmpty()
    }

    @Test
    fun `the only profile, its folders not set aside, leaves the app signed out`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val registry = FakeRegistry(stored = profile("p7", dataFormat = OldDataFormat))
        dataIn("p7")
        lockProfilesRoot()
        val graphs = graphs(registry)

        graphs.open(backgroundScope)

        assertThat(heldWhenBuilt).isEmpty()
        assertThat(graphs.current.value).isNull()
        assertThat(graphs.shown.value.state).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
    }

    /** Nothing can be renamed under the profiles' root of the files: a rename aside fails there. */
    private fun lockProfilesRoot() {
        val profiles = File(filesRoot, "profiles")
        // Whoever runs the tests as root renames it all the same: nothing can be told then.
        assumeTrue(profiles.setWritable(false) && !profiles.canWrite())
        unlock = { profiles.setWritable(true) }
    }
}
