package app.tfdn.lirelle.feature.player.car

import android.content.pm.PackageManager

/** The package of Android Auto on the phone, which draws what the car shows. */
private const val PackageName = "com.google.android.projection.gearhead"

/**
 * The SHA-256 of the certificates Google signs Android Auto with. A key Google starts to sign with is a line
 * to add here; none is to be taken away while phones still have an Android Auto signed with it.
 *
 * - `1ca8dcc0…`: read on 2026-11-25 from Android Auto 18.2 as installed on a Samsung phone, the signer of its
 *   APK signature v3 (`CN=Android, OU=Android, O=Google Inc.`).
 * - `fdb00c43…`: Android Auto's release signature as Google has long published it in the allow-list of its UAMP
 *   sample (`allowed_media_browser_callers.xml`), kept for older installs.
 */
private val GoogleCertificates = listOf(
    "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295",
    "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
).map { it.hexToByteArray() }

/**
 * Whether the package [packageName] is Android Auto: it has its name and Google signed it. The name alone is
 * no proof where Android Auto is not installed, since any app may then take it; the system vouches for the
 * name of who asks, and here for who signed the package of that name. It answers for a certificate the package
 * was signed with before a key rotation too.
 *
 * Nobody, a package that is not installed and a system that does not answer are not Android Auto.
 */
fun PackageManager.isAndroidAuto(packageName: String?): Boolean =
    packageName == PackageName && GoogleCertificates.any { isSignedWith(packageName, it) }

private fun PackageManager.isSignedWith(packageName: String, sha256: ByteArray): Boolean =
    runCatching { hasSigningCertificate(packageName, sha256, PackageManager.CERT_INPUT_SHA256) }.getOrDefault(false)
