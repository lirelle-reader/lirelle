package app.tfdn.lirelle.profile

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.ShownSession
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The real application starting on an old installation whose migration fails: the start does not crash, opens
 * nothing, deletes nothing and says that the update could not finish; started again with the cause gone, it
 * finishes and shows the account, whose data, of the old format, starts again then.
 *
 * The second start is the same application asked to open its profiles again: a process holds its files open
 * until it dies, so a second application over the same files cannot be made in one test.
 */
@RunWith(RobolectricTestRunner::class)
class UnfinishedStartTest {

    private val application: LirelleApplication get() = ApplicationProvider.getApplicationContext()
    private val migrated by lazy { MigratedInstallation(application) }
    private val nextStart = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun leaveNothingBehind() {
        runBlocking { HeldFile.release() }
        removeFakeAndroidKeystore()
        nextStart.cancel()
    }

    /** The files of this name the device holds, wherever they are: at the old place or in a profile. */
    private fun held(name: String): List<File> =
        checkNotNull(application.filesDir.parentFile).walkTopDown().filter { it.isFile && it.name == name }.toList()

    private suspend fun assertNothingOpenedNorLost() {
        val data = migrated.device.data
        assertWithMessage("shown").that(data.openProfiles().shown.value)
            .isEqualTo(ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true))
        assertWithMessage("open profile").that(data.openProfiles().current.value).isNull()
        // Still to be migrated: the proof is there, and the profile is listed without being ready.
        assertWithMessage("old session").that(application.preferencesDataStoreFile("session").exists()).isTrue()
        assertWithMessage("being moved").that(data.profileStore().beingMoved()).hasSize(1)
        assertWithMessage("being removed").that(data.profileStore().beingRemoved()).isEmpty()
        assertWithMessage("current").that(data.profileStore().current.value).isNull()
        listOf("reading.preferences_pb", "listening.preferences_pb", "downloads.preferences_pb", "a1").forEach {
            assertWithMessage(it).that(held(it)).hasSize(1)
        }
    }

    /** What the start tells logcat names the kind of failure and where in the code: nothing of the device. */
    private fun assertLogTellsNoContent() {
        val told = ShadowLog.getLogsForTag("Profiles")
        assertWithMessage("failures told").that(told.filter { "did not end" in it.msg }).hasSize(1)
        told.forEach { line ->
            assertWithMessage("failure given to the log").that(line.throwable).isNull()
            assertWithMessage("paths").that(line.msg).doesNotContain(File.separator)
            assertWithMessage("server").that(line.msg).doesNotContain("127.0.0.1")
        }
    }

    private suspend fun assertNextStartFinishes() {
        application.openProfiles(nextStart)

        migrated.assertAccountKept(migrated.shownProfile())
    }

    @Test
    @Config(application = BlockedFoldersApplication::class)
    fun `a move that fails stops the start at a message, and the next start shows the account`(): Unit =
        runBlocking {
            assertNothingOpenedNorLost()
            assertWithMessage("old downloads").that(File(application.filesDir, "downloads/$OldBook").isDirectory)
                .isTrue()
            assertLogTellsNoContent()

            BlockedFoldersApplication.blocker(application).delete()

            assertNextStartFinishes()
        }

    @Test
    @Config(application = FailingOpenApplication::class)
    fun `profiles that cannot be opened stop the start at the same message, and the next start opens them`() =
        runBlocking<Unit> {
            val profiles = TestDevice(application).data.openProfiles()

            assertWithMessage("shown").that(profiles.shown.value)
                .isEqualTo(ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true))
            assertWithMessage("open profile").that(profiles.current.value).isNull()
            val told = ShadowLog.getLogsForTag("Profiles")
            assertWithMessage("failures told").that(told.filter { "could not be opened" in it.msg }).hasSize(1)
            told.forEach { assertWithMessage("what the failure says").that(it.msg).doesNotContain("secret") }

            application.openProfiles(nextStart)

            assertWithMessage("shown").that(profiles.shown.value)
                .isEqualTo(ShownSession(SessionState.SignedOut(serverUrl = null, reason = null), profileId = null))
        }

    @Test
    @Config(application = HeldFileApplication::class)
    fun `a failure that is no I-O error stops the start the same way, and the next start shows the account`() =
        runBlocking<Unit> {
            assertNothingOpenedNorLost()
            assertLogTellsNoContent()

            HeldFile.release()

            assertNextStartFinishes()
        }
}
