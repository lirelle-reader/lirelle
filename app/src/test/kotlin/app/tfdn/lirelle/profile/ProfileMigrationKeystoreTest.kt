package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestServer
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real cipher, with a keystore that can stop answering for a while, or lose its key for good. */
private class UnsteadyCipher(private val real: TokenCipher) : TokenCipher {
    var failingForNow = false
    var keyLost = false

    override fun encrypt(plainText: String): String = real.encrypt(plainText)

    override fun decrypt(cipherText: String): String? =
        if (failingForNow || keyLost) null else real.decrypt(cipherText)

    override fun decryptOrThrow(cipherText: String): String? = when {
        failingForNow -> throw IOException("The keystore failed")
        keyLost -> null
        else -> real.decryptOrThrow(cipherText)
    }
}

/**
 * A session that cannot be read is nobody's, and what its account kept is deleted for good: so the migration
 * must be sure of it. A keystore that only fails for now ends the start's attempt with everything in place,
 * and the next one migrates.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileMigrationKeystoreTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    @get:Rule
    val folder = TemporaryFolder()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val cipher by lazy { UnsteadyCipher(KeystoreTokenCipher()) }

    private suspend fun device() = oldDevice(base, folder.newFolder(), cipher)

    private suspend fun OldDevice.failedStart(interruptAfter: String? = null): Throwable? =
        runCatching { start(cipher, interruptAfter) }.exceptionOrNull()

    @Test
    fun `a keystore that fails for now deletes nothing, and the next start migrates`(): Unit = runBlocking {
        val whole = device().also { it.start(cipher) }
        val device = device()
        val before = device.snapshot(cipher)
        cipher.failingForNow = true

        val failure = device.failedStart()

        assertThat(failure).isInstanceOf(IOException::class.java)
        cipher.failingForNow = false
        // The old installation as it was: not one file moved or deleted, and no registry made.
        assertThat(device.snapshot(cipher)).isEqualTo(before)
        assertThat(device.preferencesDataStoreFile("profiles").exists()).isFalse()

        device.start(cipher)

        assertThat(device.snapshot(cipher)).isEqualTo(whole.snapshot(cipher))
    }

    @Test
    fun `a keystore that fails for now keeps a profile being moved in, and the next start settles it`(): Unit =
        runBlocking {
            val whole = device().also { it.start(cipher) }
            val device = device()
            device.start(cipher, interruptAfter = "adopted")
            cipher.failingForNow = true

            val failure = device.failedStart()

            assertThat(failure).isInstanceOf(IOException::class.java)
            cipher.failingForNow = false
            val facts = device.registryFacts(cipher)
            assertThat(facts.moving).containsExactly("p0")
            assertThat(facts.removing).isEmpty()
            assertThat(device.preferencesDataStoreFile("session").exists()).isTrue()

            device.start(cipher)

            assertThat(device.snapshot(cipher)).isEqualTo(whole.snapshot(cipher))
        }

    @Test
    fun `a key that is lost for good is nobody's session, and what it kept goes`(): Unit = runBlocking {
        val device = device()
        cipher.keyLost = true

        device.start(cipher)

        val left = device.snapshot(cipher)
        assertThat(left.registry.state).isEqualTo(SessionState.SignedOut(TestServer, reason = null))
        assertThat(left.registry.listed).isEmpty()
        assertThat(left.files.keys.filter { it.setAsideOrGone() }).isEmpty()
    }
}
