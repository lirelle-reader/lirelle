package app.tfdn.lirelle.core.session

import java.io.IOException
import java.security.KeyStoreException

/**
 * Reversible stand-in for the Keystore cipher. `broken = true` simulates a lost key; `failingForNow = true` a
 * keystore that does not answer, which only [decryptOrThrow] tells apart from a lost key; `failingToEncrypt =
 * true` a keystore that will not encrypt, which throws as the real one does.
 */
class FakeTokenCipher(
    var broken: Boolean = false,
    var failingForNow: Boolean = false,
    var failingToEncrypt: Boolean = false,
) : TokenCipher {
    override fun encrypt(plainText: String): String {
        if (failingToEncrypt) throw KeyStoreException("The keystore would not encrypt")
        return "enc:" + plainText.reversed()
    }

    override fun decrypt(cipherText: String): String? =
        if (broken || failingForNow || !cipherText.startsWith("enc:")) {
            null
        } else {
            cipherText.removePrefix("enc:").reversed()
        }

    override fun decryptOrThrow(cipherText: String): String? {
        if (failingForNow) throw IOException("The keystore failed")
        return decrypt(cipherText)
    }
}
