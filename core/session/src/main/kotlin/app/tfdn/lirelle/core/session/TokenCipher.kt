package app.tfdn.lirelle.core.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenCipher {
    fun encrypt(plainText: String): String

    /** Returns null when [cipherText] cannot be decrypted, for example after the key was lost. */
    fun decrypt(cipherText: String): String?

    /**
     * [decrypt] for who is about to delete what the text gave access to, and must first know why it cannot be
     * read: a keystore that fails for a moment is not an account that left.
     *
     * @return null when [cipherText] can never be read again: the key is gone or was invalidated for good, or
     *   the text is not what this key encrypted.
     * @throws IOException when it could not be read for now: any other failure of the keystore. A cipher that
     *   cannot tell the two apart never throws.
     */
    fun decryptOrThrow(cipherText: String): String? = decrypt(cipherText)
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore.
 * The Keystore does not exist on the JVM: the tests of the application run this over keys kept in memory, and
 * what the real Keystore throws is verified on a device.
 */
class KeystoreTokenCipher : TokenCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun decrypt(cipherText: String): String? = try {
        decrypted(cipherText)
    } catch (_: Exception) {
        null
    }

    /**
     * Never to be read again, and null: a text that is not what [encrypt] writes
     * ([IllegalArgumentException]: not Base64, or too short to hold what starts it), one that this key does
     * not authenticate ([AEADBadTagException]: the key was lost and made again, or the text altered), and a
     * key the system has invalidated for good ([KeyPermanentlyInvalidatedException]).
     *
     * Everything else the keystore or its provider throws — a [GeneralSecurityException] of another kind, a
     * `ProviderException` or any other [RuntimeException] — is taken for a failure of the moment: the
     * keystore's daemon not answering, a device still locked. It is not known to last, so nothing may be
     * deleted on its account.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun decryptOrThrow(cipherText: String): String? = try {
        decrypted(cipherText)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: AEADBadTagException) {
        null
    } catch (_: KeyPermanentlyInvalidatedException) {
        null
    } catch (failure: GeneralSecurityException) {
        throw IOException("The keystore could not decrypt for now", failure)
    } catch (failure: RuntimeException) {
        throw IOException("The keystore could not decrypt for now", failure)
    }

    private fun decrypted(cipherText: String): String {
        val bytes = Base64.getDecoder().decode(cipherText)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagBits, bytes, 0, IvBytes))
        return String(cipher.doFinal(bytes, IvBytes, bytes.size - IvBytes), Charsets.UTF_8)
    }

    /**
     * Looked up or created once, under the lock of `lazy`: generating a key for an alias that
     * already has one replaces it, which would make what the first key encrypted unreadable.
     */
    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(Provider).apply { load(null) }
        keyStore.getKey(Alias, null) as? SecretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KeyBits)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Provider).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val Provider = "AndroidKeyStore"
        const val Alias = "lirelle.session"
        const val Transformation = "AES/GCM/NoPadding"
        const val KeyBits = 256
        const val TagBits = 128
        const val IvBytes = 12
    }
}
