package app.tfdn.lirelle.core.session.profile

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileFoldersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val files by lazy { folder.newFolder("files") }
    private val cache by lazy { folder.newFolder("cache") }
    private val roots by lazy { ProfileRoots(files, cache) }
    private val key = stringPreferencesKey("recent")

    @Test
    fun `a profile lives under profiles and its id, in the files and in the cache`() {
        assertThat(roots.of("ab12cd34"))
            .isEqualTo(ProfileFolders(File(files, "profiles/ab12cd34"), File(cache, "profiles/ab12cd34")))
    }

    @Test
    fun `a store keeps its name inside the profile's files`() {
        assertThat(roots.of("p1").dataStore("searches"))
            .isEqualTo(File(files, "profiles/p1/datastore/searches.preferences_pb"))
    }

    @Test
    fun `no profile has a folder before one is made`() {
        assertThat(roots.present()).isEmpty()
    }

    @Test
    fun `a profile is present when it has a folder in either root`() {
        roots.of("kept").files.mkdirs()
        roots.of("made-again").cache.mkdirs()

        assertThat(roots.present()).containsExactly("kept", "made-again")
    }

    @Test
    fun `deleting a profile takes both its folders and what they hold, and no other's`() {
        val gone = roots.of("gone")
        File(gone.files, "downloads/7").apply { parentFile?.mkdirs() }.writeText("book")
        File(gone.cache, "covers/7").apply { parentFile?.mkdirs() }.writeText("cover")
        roots.of("stays").files.mkdirs()

        roots.delete("gone")

        assertThat(gone.files.exists()).isFalse()
        assertThat(gone.cache.exists()).isFalse()
        assertThat(roots.present()).containsExactly("stays")
    }

    @Test
    fun `a store lets go of its file when the profile's life ends, and can be opened again`() = runBlocking {
        val folders = roots.of("p1")
        val life = CoroutineScope(SupervisorJob())
        folders.newDataStore("searches", life).edit { it[key] = "mars" }

        life.coroutineContext.job.cancelAndJoin()

        // Opening a file that another store still holds fails: this one was let go of.
        val again = CoroutineScope(SupervisorJob())
        assertThat(folders.newDataStore("searches", again).data.first()[key]).isEqualTo("mars")
        again.coroutineContext.job.cancelAndJoin()
        roots.delete("p1")
        assertThat(roots.present()).isEmpty()
    }
}
