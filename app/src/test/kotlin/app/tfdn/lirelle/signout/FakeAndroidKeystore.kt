package app.tfdn.lirelle.signout

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import org.junit.rules.ExternalResource

private const val Name = "AndroidKeyStore"
private const val KeyBits = 256

private val keys = ConcurrentHashMap<String, SecretKey>()

/**
 * The Android Keystore does not exist on the JVM: this stands for it, with keys in memory, so that the real
 * session store can sign an account in.
 */
class FakeAndroidKeystore : ExternalResource() {
    override fun before() = installFakeAndroidKeystore()

    override fun after() = removeFakeAndroidKeystore()
}

/**
 * Installs it without the rule: an application that finds an older installation at its start needs it before
 * any rule of its test has run.
 */
internal fun installFakeAndroidKeystore() {
    keys.clear()
    keystoreFailure = null
    Security.addProvider(FakeProvider())
}

/** What the keystore throws when asked for a key, while it is set: a keystore that does not answer. */
@Volatile
internal var keystoreFailure: RuntimeException? = null

/** The keystore loses every key, as when the device's lock screen is reset: the next one asked for is new. */
internal fun loseKeystoreKeys() = keys.clear()

internal fun removeFakeAndroidKeystore() {
    keystoreFailure = null
    Security.removeProvider(Name)
}

private class FakeProvider : Provider(Name, 1.0, "Keys in memory, for tests") {
    init {
        put("KeyStore.$Name", FakeKeyStore::class.java.name)
        put("KeyGenerator.AES", FakeKeyGenerator::class.java.name)
    }
}

class FakeKeyGenerator : KeyGeneratorSpi() {
    private var alias = ""

    override fun engineInit(random: SecureRandom?) = Unit

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).keystoreAlias
    }

    override fun engineInit(keySize: Int, random: SecureRandom?) = Unit

    override fun engineGenerateKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(KeyBits) }.generateKey().also { keys[alias] = it }
}

class FakeKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? {
        keystoreFailure?.let { throw it }
        return keys[alias]
    }

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String?): Certificate? = null

    override fun engineGetCreationDate(alias: String?): Date? = null

    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
        Unit

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit

    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

    override fun engineSize(): Int = keys.size

    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

    override fun engineIsCertificateEntry(alias: String?): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
