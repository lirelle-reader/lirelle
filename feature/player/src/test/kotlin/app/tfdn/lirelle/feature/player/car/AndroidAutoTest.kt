package app.tfdn.lirelle.feature.player.car

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.testing.AndroidAutoCertificates
import app.tfdn.lirelle.core.testing.AndroidAutoPackage
import app.tfdn.lirelle.core.testing.SignedPackages
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Who is Android Auto: the package of that name that Google signed. */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [SignedPackages::class])
class AndroidAutoTest {

    private val packages = ApplicationProvider.getApplicationContext<Context>().packageManager

    @After
    fun uninstall() = SignedPackages.forget()

    @Test
    fun `the package of Android Auto's name signed by Google is Android Auto, with either certificate`() {
        listOf(AndroidAutoCertificates.Current, AndroidAutoCertificates.Older).forEach { certificate ->
            SignedPackages.sign(AndroidAutoPackage, certificate)

            assertThat(packages.isAndroidAuto(AndroidAutoPackage)).isTrue()
        }
        // A key that was rotated: the system answers for a certificate of the lineage too.
        SignedPackages.sign(AndroidAutoPackage, AndroidAutoCertificates.Stranger, AndroidAutoCertificates.Older)
        assertThat(packages.isAndroidAuto(AndroidAutoPackage)).isTrue()
    }

    @Test
    fun `an app that took Android Auto's name is not Android Auto - it is not signed by Google`() {
        SignedPackages.sign(AndroidAutoPackage, AndroidAutoCertificates.Stranger)

        assertThat(packages.isAndroidAuto(AndroidAutoPackage)).isFalse()
    }

    @Test
    fun `Android Auto that is not installed is nobody`() {
        assertThat(packages.isAndroidAuto(AndroidAutoPackage)).isFalse()
    }

    @Test
    fun `another package is not Android Auto, even signed by Google, and nobody is not either`() {
        SignedPackages.sign("com.google.android.apps.maps", AndroidAutoCertificates.Current)
        SignedPackages.sign(AndroidAutoPackage, AndroidAutoCertificates.Current)

        assertThat(packages.isAndroidAuto("com.google.android.apps.maps")).isFalse()
        assertThat(packages.isAndroidAuto("com.example.watch")).isFalse()
        assertThat(packages.isAndroidAuto(null)).isFalse()
    }
}

/** The device's own package manager, which Robolectric gives no answer about signatures. */
@RunWith(AndroidJUnit4::class)
class AndroidAutoUnansweredTest {

    @Test
    fun `a package manager that does not answer makes nobody Android Auto`() {
        val packages = ApplicationProvider.getApplicationContext<Context>().packageManager

        assertThat(packages.isAndroidAuto(AndroidAutoPackage)).isFalse()
    }
}
