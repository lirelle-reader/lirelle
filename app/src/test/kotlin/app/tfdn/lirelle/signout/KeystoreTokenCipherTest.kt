package app.tfdn.lirelle.signout

import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.security.ProviderException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Why a text cannot be decrypted, as the real cipher tells it over a keystore in memory: what can never be
 * read again is nobody's, what cannot be read for now is not. The real keystore's own failures are seen on a
 * device only.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreTokenCipherTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    @Test
    fun `what was encrypted is read back, either way`() {
        val cipher = KeystoreTokenCipher()
        val text = cipher.encrypt("tokens")

        assertThat(cipher.decrypt(text)).isEqualTo("tokens")
        assertThat(cipher.decryptOrThrow(text)).isEqualTo("tokens")
    }

    @Test
    fun `a text encrypted with a key that is gone can never be read again`() {
        val text = KeystoreTokenCipher().encrypt("tokens")
        loseKeystoreKeys()
        val afterwards = KeystoreTokenCipher()

        assertThat(afterwards.decryptOrThrow(text)).isNull()
        assertThat(afterwards.decrypt(text)).isNull()
    }

    @Test
    fun `a text that is not what the cipher wrote can never be read`() {
        val cipher = KeystoreTokenCipher()

        assertThat(cipher.decryptOrThrow("not base64 !")).isNull()
        assertThat(cipher.decryptOrThrow("c2hvcnQ=")).isNull()
        assertThat(cipher.decryptOrThrow(cipher.encrypt("tokens").reversed())).isNull()
    }

    @Test
    fun `a keystore that does not answer cannot read for now, and reads once it answers`() {
        val text = KeystoreTokenCipher().encrypt("tokens")
        val cipher = KeystoreTokenCipher()
        keystoreFailure = ProviderException("Keystore not available")

        assertThrows(IOException::class.java) { cipher.decryptOrThrow(text) }
        // For who only wants the tokens, it is still simply not readable.
        assertThat(cipher.decrypt(text)).isNull()

        keystoreFailure = null
        assertThat(cipher.decryptOrThrow(text)).isEqualTo("tokens")
    }
}
