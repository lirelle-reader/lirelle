package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.assumeFoldersCanBeLocked
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * What the migration only tidies up — the old audio cache, the old session file once everything is moved —
 * never costs the user the start: a failure there is told to the log, and the migration ends all the same.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileMigrationBestEffortTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    @get:Rule
    val folder = TemporaryFolder()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val cipher by lazy { KeystoreTokenCipher() }

    private suspend fun device() = oldDevice(base, folder.newFolder(), cipher)

    private fun warnings(): List<String> = ShadowLog.getLogsForTag("Profiles").map { it.msg }

    private fun assertWarnedWithoutContent(device: OldDevice) {
        assertWithMessage("warnings").that(warnings()).isNotEmpty()
        warnings().forEach { assertThat(it).doesNotContain(checkNotNull(device.filesDir.parent)) }
    }

    @Test
    fun `an old audio cache that cannot be touched does not stop the migration`(): Unit = runBlocking {
        assumeFoldersCanBeLocked()
        val device = device()
        // Neither moved nor emptied: a folder that cannot be written loses neither its place nor its folders.
        val audio = File(device.cacheDir, "audio").apply { setWritable(false) }
        try {
            val parts = device.start(cipher)

            assertThat(parts.last()).isEqualTo("finished")
            assertThat(device.registryFacts(cipher).state).isEqualTo(SessionState.SignedIn(OldSession))
            assertThat(device.preferencesDataStoreFile("session").exists()).isFalse()
            assertThat(audio.exists()).isTrue()
            assertWarnedWithoutContent(device)
        } finally {
            audio.setWritable(true)
        }
    }

    @Test
    fun `a scratch file that cannot be deleted does not stop the migration`(): Unit = runBlocking {
        assumeFoldersCanBeLocked()
        val device = device()
        // In place of the scratch file, a folder that holds something and cannot be written.
        val scratch = File(device.filesDir, "datastore/reading.preferences_pb.tmp").apply { delete() }
        File(scratch, "held").apply { parentFile?.mkdirs() }.writeText("x")
        scratch.setWritable(false)
        try {
            val parts = device.start(cipher)

            assertThat(parts.last()).isEqualTo("finished")
            assertThat(device.registryFacts(cipher).state).isEqualTo(SessionState.SignedIn(OldSession))
            assertThat(readPreferences(File(device.filesDir, "profiles/p0/datastore/reading.preferences_pb")))
                .containsKey("positions")
            assertWarnedWithoutContent(device)
        } finally {
            scratch.setWritable(true)
        }
    }

    @Test
    fun `the old audio cache is set aside under a name that is free`(): Unit = runBlocking {
        val device = device()
        // What an earlier attempt set aside, and no start has swept yet.
        val earlier = File(device.cacheDir, "profiles/old-audio/kept").apply { parentFile?.mkdirs() }
        earlier.writeText("earlier")

        device.start(cipher)

        assertThat(File(device.cacheDir, "audio").exists()).isFalse()
        assertThat(File(device.cacheDir, "profiles/old-audio-2/0/stream.exo").readText()).isEqualTo("audio")
        assertThat(earlier.readText()).isEqualTo("earlier")
        assertThat(warnings()).isEmpty()
    }

    @Test
    fun `an old session file that cannot be deleted does not stop the migration, and goes at the next start`() =
        runBlocking<Unit> {
            assumeFoldersCanBeLocked()
            val whole = device().also { it.start(cipher) }
            val device = device()
            val datastore = File(device.filesDir, "datastore")
            try {
                // Everything is moved and the profile is ready: only the old file is left to delete.
                val parts = device.start(cipher) { part -> if (part == "settled") datastore.setWritable(false) }

                assertThat(parts.last()).isEqualTo("finished")
                assertThat(device.preferencesDataStoreFile("session").exists()).isTrue()
                assertWarnedWithoutContent(device)
            } finally {
                datastore.setWritable(true)
            }
            assertThat(device.registryFacts(cipher).state).isEqualTo(SessionState.SignedIn(OldSession))

            val next = device.start(cipher)

            assertThat(next).containsExactly("finished")
            assertThat(device.snapshot(cipher)).isEqualTo(whole.snapshot(cipher))
        }
}
