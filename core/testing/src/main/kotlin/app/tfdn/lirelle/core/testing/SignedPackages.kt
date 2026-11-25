package app.tfdn.lirelle.core.testing

import android.content.pm.PackageManager
import org.junit.rules.ExternalResource
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

/** Android Auto on the phone, which draws what the car shows. */
const val AndroidAutoPackage = "com.google.android.projection.gearhead"

/**
 * The SHA-256 of the certificates Google signs Android Auto with, written here apart from the app's own list:
 * a test that passes says the app knows these very ones.
 */
object AndroidAutoCertificates {
    /** Read from Android Auto 18.2 on a phone, on 2026-11-25. */
    const val Current = "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"

    /** The release signature of the allow-list of Google's UAMP sample. */
    const val Older = "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf"

    /** Nobody's: what an app that only took Android Auto's name is signed with. */
    const val Stranger = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
}

/**
 * The device's package manager, which also answers `PackageManager.hasSigningCertificate`: Robolectric's own
 * leaves it to a system that is not there. A test class takes it with `@Config(shadows = [SignedPackages::class])`
 * and says who signed what with [sign], or with the rule [InstalledAndroidAuto].
 *
 * Only SHA-256 digests are known, as the app asks: no certificate can be made that has Google's digest.
 */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class SignedPackages : ShadowApplicationPackageManager() {

    @Implementation
    fun hasSigningCertificate(packageName: String, certificate: ByteArray, type: Int): Boolean =
        type == PackageManager.CERT_INPUT_SHA256 && certificate.toHexString() in signed[packageName].orEmpty()

    companion object {
        private val signed = mutableMapOf<String, Set<String>>()

        /** From now on [packageName] is installed and signed with the certificates of these SHA-256, in hex. */
        fun sign(packageName: String, vararg sha256: String) {
            signed[packageName] = sha256.toSet()
        }

        /** Nobody signed anything any more: Robolectric does not do it between tests, [InstalledAndroidAuto] does. */
        fun forget() = signed.clear()
    }
}

/**
 * Android Auto installed on the device and signed with the certificate of [sha256], Google's current one unless
 * said otherwise; uninstalled after the test. The test class takes the shadow [SignedPackages].
 */
class InstalledAndroidAuto(private val sha256: String = AndroidAutoCertificates.Current) : ExternalResource() {

    override fun before() = SignedPackages.sign(AndroidAutoPackage, sha256)

    override fun after() = SignedPackages.forget()
}
