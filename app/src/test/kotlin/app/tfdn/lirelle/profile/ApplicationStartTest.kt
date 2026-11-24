package app.tfdn.lirelle.profile

import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real application started on a device that already holds an installation of the version before the
 * profiles: the files are there before anything of the application runs ([OldInstallationApplication]), as an
 * update finds them, and the start is the application's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OldInstallationApplication::class)
class ApplicationStartTest {

    private val application: LirelleApplication get() = ApplicationProvider.getApplicationContext()
    private val migrated by lazy { MigratedInstallation(application) }

    @After
    fun leaveNothingBehind() = removeFakeAndroidKeystore()

    /**
     * Nothing of the application opens a store before the migration: one that did would hold a file the
     * migration then finds taken, or would read the old place and find it empty afterwards. The account's data,
     * of the old format, starts again when its profile opens.
     */
    @Test
    fun `an old installation keeps its account and the device's settings through the start on it`(): Unit =
        runBlocking {
            val profile = migrated.shownProfile()

            migrated.assertAccountKept(profile)
            assertThat(migrated.device.data.openProfiles().shown.value.updateUnfinished).isFalse()
        }

    @Test
    fun `the old audio cache is out of the way when the start returns, and gone once its sweep has passed`() =
        runBlocking<Unit> {
            // Up to a gigabyte of files: renamed by the start, not deleted by it.
            assertThat(File(application.cacheDir, "audio").exists()).isFalse()

            until("what was set aside is deleted") { migrated.setAside().isEmpty() }

            val profile = migrated.shownProfile()
            assertThat(migrated.device.profileFolders().map { it.name }.distinct()).containsExactly(profile)
        }
}
