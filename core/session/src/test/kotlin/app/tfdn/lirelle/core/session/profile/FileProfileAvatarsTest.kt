package app.tfdn.lirelle.core.session.profile

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val First = "/api/v1/users/1/avatar?v=1"
private const val Second = "/api/v1/users/1/avatar?v=2"

@OptIn(ExperimentalCoroutinesApi::class)
class FileProfileAvatarsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val avatars by lazy { File(folder.root, "avatars") }

    private fun TestScope.store() = FileProfileAvatars(avatars, UnconfinedTestDispatcher(testScheduler))

    /** Every picture told for [profileId] from now on, in order. */
    private fun TestScope.told(store: ProfileAvatars, profileId: String): List<File?> {
        val told = mutableListOf<File?>()
        backgroundScope.launch { store.picture(profileId).collect { told += it } }
        return told
    }

    @Test
    fun `nothing is kept before a picture is`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()

        assertThat(store.picture("p0").first()).isNull()
        assertThat(store.keptFor("p0")).isNull()
    }

    @Test
    fun `a picture kept is read back, with what it was fetched for`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()

        store.keep("p0", First, byteArrayOf(1, 2, 3))

        assertThat(store.picture("p0").first()?.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(store.keptFor("p0")).isEqualTo(First)
        assertThat(store.picture("p1").first()).isNull()
        assertThat(avatars.list()?.toList()).containsExactly("p0", "p0.for")
    }

    @Test
    fun `it is still there for another process`() = runTest(UnconfinedTestDispatcher()) {
        store().keep("p0", First, byteArrayOf(1))

        val later = store()

        assertThat(later.picture("p0").first()?.readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(later.keptFor("p0")).isEqualTo(First)
    }

    @Test
    fun `a second picture replaces the first and is told as another file`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val told = told(store, "p0")
        store.keep("p0", First, byteArrayOf(1))

        store.keep("p0", Second, byteArrayOf(2, 2))

        assertThat(told).hasSize(3)
        assertThat(told[0]).isNull()
        assertThat(told[2]?.readBytes()).isEqualTo(byteArrayOf(2, 2))
        // The same place, and yet not an equal file: who shows the picture loads it again.
        assertThat(told[2]?.path).isEqualTo(told[1]?.path)
        assertThat(told[2]).isNotEqualTo(told[1])
        assertThat(store.keptFor("p0")).isEqualTo(Second)
    }

    @Test
    fun `the picture of another profile tells nothing`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.keep("p0", First, byteArrayOf(1))
        val told = told(store, "p0")

        store.keep("p1", First, byteArrayOf(9))
        store.delete("p1")

        assertThat(told).hasSize(1)
    }

    @Test
    fun `a picture deleted is told as none`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.keep("p0", First, byteArrayOf(1))
        val told = told(store, "p0")

        store.delete("p0")

        assertThat(told.last()).isNull()
        assertThat(store.keptFor("p0")).isNull()
        assertThat(avatars.list()).isEmpty()
    }

    @Test
    fun `a picture kept again after a deletion is not the one of before`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.keep("p0", First, byteArrayOf(1))
        val before = store.picture("p0").first()
        store.delete("p0")

        store.keep("p0", First, byteArrayOf(2))

        assertThat(store.picture("p0").first()).isNotEqualTo(before)
    }

    @Test
    fun `a sweep keeps the listed profiles' pictures and deletes the others`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.keep("p0", First, byteArrayOf(1))
        store.keep("p1", First, byteArrayOf(2))
        store.keep("p2", First, byteArrayOf(3))
        val told = told(store, "p1")

        store.sweep(setOf("p0", "p2"))

        assertThat(avatars.list()?.toList()).containsExactly("p0", "p0.for", "p2", "p2.for")
        assertThat(told.last()).isNull()
        assertThat(store.keptFor("p0")).isEqualTo(First)
    }

    @Test
    fun `a sweep with no folder yet does nothing`() = runTest(UnconfinedTestDispatcher()) {
        store().sweep(emptySet())

        assertThat(avatars.exists()).isFalse()
    }

    @Test
    fun `a write that fails leaves the picture there was`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.keep("p0", First, byteArrayOf(1))
        val told = told(store, "p0")
        // Nothing can be written where the new picture is prepared: a folder is in its place.
        File(avatars, "p0.tmp/in-the-way").apply { parentFile?.mkdirs() }.writeText("x")

        val failure = runCatching { store.keep("p0", Second, byteArrayOf(2)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)

        assertThat(told).hasSize(1)
        assertThat(told.single()?.readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(store.keptFor("p0")).isEqualTo(First)
    }

    @Test
    fun `a picture in place whose url could not be written is told, and is not said to be fetched`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store()
            store.keep("p0", First, byteArrayOf(1))
            val told = told(store, "p0")
            // The picture can be renamed into its place, its `.for` cannot: a folder that is not empty is there.
            File(avatars, "p0.for").delete()
            File(avatars, "p0.for/in-the-way").apply { parentFile?.mkdirs() }.writeText("x")

            val failure = runCatching { store.keep("p0", Second, byteArrayOf(2, 2)) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(told).hasSize(2)
            assertThat(told.last()?.readBytes()).isEqualTo(byteArrayOf(2, 2))
            assertThat(told.last()).isNotEqualTo(told.first())
            // Not said to be the new one's: it is fetched again.
            assertThat(store.keptFor("p0")).isNull()
            assertThat(avatars.list()?.toList()).containsExactly("p0", "p0.for")
        }

    @Test
    fun `what a write cut short left is not a picture, and goes at the sweep`() = runTest(UnconfinedTestDispatcher()) {
        File(avatars, "p0.tmp").apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(7))
        File(avatars, "p0.for.tmp").writeText(Second)
        val store = store()

        assertThat(store.picture("p0").first()).isNull()
        assertThat(store.keptFor("p0")).isNull()

        store.sweep(setOf("p0"))

        assertThat(avatars.list()).isEmpty()
    }
}
