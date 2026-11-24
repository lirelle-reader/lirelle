package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An installation of the version before the profiles, migrated by the application's own migration and read
 * back through the application's real graph and its real stores ([MigratedInstallation]): its account and the
 * device's settings are kept, and the account's data, of the old format, starts again. The application
 * started on an empty device here, and the old installation is written under it: the start itself on an old
 * installation is `ApplicationStartTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileMigrationTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val migrated = MigratedInstallation(context)

    @Test
    fun `an installation from before the profiles finds its account in its first profile, which starts again`(): Unit =
        runBlocking {
            // Without the files that are the device's alone: the application that started holds one open.
            OldInstallation(context, KeystoreTokenCipher()).write(untouched = false)

            (context as LirelleApplication).migration.run()

            migrated.assertAccountKept(migrated.shownProfile(), untouched = false)
            // No start came after this migration, so no sweep: the old audio waits where it was set aside.
            assertThat(migrated.setAside().map { it.name }).containsExactly("old-audio")
        }
}
