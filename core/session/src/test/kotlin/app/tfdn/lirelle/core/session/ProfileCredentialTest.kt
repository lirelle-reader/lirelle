package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A profile holds a credential, whose sort its kind of server says. The entry keeps the shape it had when it could
 * only hold BookOrbit's tokens: a BookOrbit entry stays byte for byte as the version before wrote it.
 */
class ProfileCredentialTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") }

    private suspend fun loaded(dataStore: DataStore<Preferences>) =
        DataStoreProfileRegistry(dataStore, FakeTokenCipher()) { "p${counter++}" }.also { it.load() }

    private suspend fun stored(dataStore: DataStore<Preferences>) = dataStore.data.first()[RegistryKeys.Profiles]!!

    @Test
    fun `a BookOrbit entry of the version before reads and writes back byte for byte`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val registry = codec.decodeRegistry(RegistryOfBefore)!!
        assertThat(registry.unread).isEmpty()

        val profiles = registry.profiles.map { codec.encode(codec.decode(it)!!) }

        assertThat(codec.encode(StoredRegistry(profiles, registry.current))).isEqualTo(RegistryOfBefore)
    }

    @Test
    fun `a profile to sign in again and one being removed, of the version before, write back byte for byte`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val registry = codec.decodeRegistry(OtherRegistryOfBefore)!!
        assertThat(registry.unread).isEmpty()

        val read = registry.profiles.associate { it.id to codec.decode(it)!! }
        val profiles = registry.profiles.map { codec.encode(read.getValue(it.id), it.state) }

        assertThat(codec.encode(StoredRegistry(profiles, registry.current))).isEqualTo(OtherRegistryOfBefore)
        assertThat(read.getValue("bob").signInNeeded).isTrue()
        assertThat(read.getValue("bob").session.user.canDownload).isFalse()
        assertThat(read.getValue("bob").session.user.avatarUrl).isEqualTo("https://books.example.com/avatar/2")
        assertThat(registry.profiles.map { it.state }).containsExactly(StoredState.Ready, StoredState.Removing)
    }

    @Test
    fun `an entry holding a credential of another sort than its kind's is kept as written, and the app starts`() =
        runTest {
            val dataStore = dataStore()
            val written = RegistryOfBefore.replace("}],", "},$Mismatched],")
            dataStore.edit { it[RegistryKeys.Profiles] = written }

            val registry = loaded(dataStore)

            assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
            assertThat(registry.profile("m1")).isNull()
            assertThat(registry.listedIds()).containsExactly("ann", "m1")
            assertThat(registry.beingRemoved()).isEmpty()
            assertThat(stored(dataStore)).isEqualTo(written)
        }

    @Test
    fun `an entry of another sort is kept when the keystore stops answering after reading it once`() = runTest {
        val dataStore = dataStore()
        val written = RegistryOfBefore.replace("}],", "},$Mismatched],")
        dataStore.edit { it[RegistryKeys.Profiles] = written }
        val cipher = SilentAfterFirstTry(MismatchedKey.content)

        val registry = DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

        assertThat(cipher.tries).isAtLeast(1)
        assertThat(registry.profile("m1")).isNull()
        assertThat(registry.listedIds()).containsExactly("ann", "m1")
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(stored(dataStore)).isEqualTo(written)
    }

    @Test
    fun `the same kind, server and account signing in again takes over an entry of another sort`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[RegistryKeys.Profiles] = RegistryOfBefore.replace("}],", "},$Mismatched],") }
        val registry = loaded(dataStore)
        val mia = session.copy(user = UserProfile(id = "9", username = "mia", name = "Mia", email = null))

        registry.save(mia)

        assertThat(registry.profile("m1")?.session).isEqualTo(mia)
        assertThat(registry.listedIds()).containsExactly("ann", "m1")
        assertThat(registry.current.value?.id).isEqualTo("m1")
        assertThat(registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `a BookOrbit profile of the version before signed in to again is written as it was`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[RegistryKeys.Profiles] = RegistryOfBefore }
        val registry = loaded(dataStore)

        registry.save(session)

        assertThat(stored(dataStore)).isEqualTo(RegistryOfBefore)
    }

    @Test
    fun `a BookOrbit entry holds BookOrbit's tokens with the session's id`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[RegistryKeys.Profiles] = RegistryOfBefore }

        val profile = loaded(dataStore).profile("ann")!!

        assertThat(profile.kind).isEqualTo(ServerKind.BookOrbit)
        assertThat(profile.session.credential).isEqualTo(Credential.SessionTokens(sessionId = 42, Sample.tokens))
    }

    @Test
    fun `a key round trips encrypted, with its id and without a session id`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val keyed = session.copy(credential = Key)

        val stored = codec.encode(keyed)

        assertThat(stored.sessionId).isNull()
        assertThat(codec.encodeSession(keyed)).doesNotContain("sessionId")
        assertThat(codec.encodeSession(keyed)).doesNotContain(Key.key)
        assertThat(codec.decode(stored, CredentialFormat.ApiKey)).isEqualTo(keyed)
    }

    @Test
    fun `a Komga profile's key round trips through the registry, encrypted`() = runTest {
        val dataStore = dataStore()
        val keyed = session.copy(credential = Key)

        loaded(dataStore).save(keyed, ServerKind.Komga)
        val again = loaded(dataStore).profile("p0")!!

        assertThat(again.kind).isEqualTo(ServerKind.Komga)
        assertThat(again.session).isEqualTo(keyed)
        assertThat(stored(dataStore)).contains("\"kind\":\"Komga\"")
        assertThat(stored(dataStore)).doesNotContain(Key.key)
        assertThat(stored(dataStore)).doesNotContain("sessionId")
    }

    @Test
    fun `a key stored before keys could be the user's own reads as one the app made`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val stored = codec.encode(session.copy(credential = Key)).copy(encryptedTokens = MismatchedKey.content)

        val read = codec.decode(stored, CredentialFormat.ApiKey)!!

        assertThat(read.credential).isEqualTo(Credential.ApiKey(key = "secret", keyId = "0K3Y1D", ownedByApp = true))
    }

    @Test
    fun `a key the app made is stored as it was before keys could be the user's own`() {
        val cipher = FakeTokenCipher()

        val stored = ProfileCodec(cipher).encode(session.copy(credential = Key))

        assertThat(cipher.decrypt(stored.encryptedTokens)).isEqualTo("""{"key":"${Key.key}","keyId":"0K3Y1D"}""")
    }

    @Test
    fun `a key the user pasted is still the user's own once written and read`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val pasted = session.copy(credential = Key.copy(ownedByApp = false))

        val read = codec.decode(codec.encode(pasted), CredentialFormat.ApiKey)

        assertThat(read).isEqualTo(pasted)
        assertThat((read?.credential as Credential.ApiKey).ownedByApp).isFalse()
    }

    @Test
    fun `a Kavita profile's key round trips through the registry, the user's own or the app's`() = runTest {
        val dataStore = dataStore()
        val pasted = session.copy(credential = Key.copy(ownedByApp = false))
        val made = session.copy(serverUrl = "https://other.example.com", credential = Key.copy(keyId = "0K3Y2D"))

        loaded(dataStore).apply {
            save(pasted, ServerKind.Kavita)
            save(made, ServerKind.Kavita)
        }
        val again = loaded(dataStore)

        assertThat(again.profile("p0")?.kind).isEqualTo(ServerKind.Kavita)
        assertThat(again.profile("p0")?.session).isEqualTo(pasted)
        assertThat(again.profile("p1")?.session).isEqualTo(made)
        assertThat(stored(dataStore)).contains("\"kind\":\"Kavita\"")
        assertThat(stored(dataStore)).doesNotContain(Key.key)
    }

    @Test
    fun `an account of the same server and id under another kind makes its own profile`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        val keyed = session.copy(credential = Key)

        registry.save(session)
        val replaced = registry.save(keyed, ServerKind.Komga)

        assertThat(replaced).isNull()
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        assertThat(registry.profile("p0")?.kind).isEqualTo(ServerKind.BookOrbit)
        assertThat(registry.profile("p0")?.session).isEqualTo(session)
        assertThat(registry.profile("p1")?.kind).isEqualTo(ServerKind.Komga)
        assertThat(registry.current.value?.id).isEqualTo("p1")
    }

    @Test
    fun `what the encrypted part holds is read as the kind says, and nothing else`() {
        val codec = ProfileCodec(FakeTokenCipher())

        assertThat(codec.decode(codec.encode(session.copy(credential = Key)), CredentialFormat.SessionTokens)).isNull()
        assertThat(codec.decode(codec.encode(session), CredentialFormat.ApiKey)).isNull()
    }

    @Test
    fun `a profile's credential is of the sort its kind holds`() {
        val keyed = session.copy(credential = Key)

        val error = runCatching { Profile("p0", ServerKind.BookOrbit, keyed) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a key never prints`() {
        assertThat(Key.toString()).doesNotContain(Key.key)
        assertThat(Key.toString()).contains(Key.keyId)
        assertThat(Key.copy(ownedByApp = false).toString()).doesNotContain(Key.key)
    }

    @Test
    fun `rotating tokens round trip encrypted, with their sign-in and without a session id`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val rotating = session.copy(credential = Rotating)

        val stored = codec.encode(rotating)

        assertThat(stored.sessionId).isNull()
        assertThat(codec.encodeSession(rotating)).doesNotContain("sessionId")
        assertThat(codec.encodeSession(rotating)).doesNotContain(Rotating.tokens.refreshToken)
        assertThat(codec.encodeSession(rotating)).doesNotContain(Rotating.signInId)
        assertThat(codec.decode(stored, CredentialFormat.RotatingTokens)).isEqualTo(rotating)
    }

    @Test
    fun `an Audiobookshelf profile's rotating tokens round trip through the registry, encrypted`() = runTest {
        val dataStore = dataStore()
        val rotating = session.copy(credential = Rotating)

        loaded(dataStore).save(rotating, ServerKind.Audiobookshelf)
        val again = loaded(dataStore).profile("p0")!!

        assertThat(again.kind).isEqualTo(ServerKind.Audiobookshelf)
        assertThat(again.session).isEqualTo(rotating)
        assertThat(stored(dataStore)).contains("\"kind\":\"Audiobookshelf\"")
        assertThat(stored(dataStore)).doesNotContain(Rotating.tokens.accessToken)
        assertThat(stored(dataStore)).doesNotContain("sessionId")
    }

    @Test
    fun `renewed rotating tokens are the same sign-in, another sign-in is not`() {
        val renewed = Rotating.copy(tokens = Sample.tokens.copy(accessToken = "access-2", refreshToken = "refresh-2"))
        val another = Rotating.copy(signInId = "5d0e6a3b-0c1f-4e27-9a8b-2f6c7d1e9b40")

        assertThat(Rotating.isSameSignIn(renewed)).isTrue()
        assertThat(renewed.isSameSignIn(Rotating)).isTrue()
        assertThat(Rotating.isSameSignIn(another)).isFalse()
        assertThat(Rotating.isSameSignIn(Credential.SessionTokens(sessionId = 42, Rotating.tokens))).isFalse()
    }

    @Test
    fun `an Audiobookshelf profile holds rotating tokens, and no other kind does`() {
        val rotating = session.copy(credential = Rotating)

        assertThat(Profile("p0", ServerKind.Audiobookshelf, rotating).session).isEqualTo(rotating)
        listOf(session, session.copy(credential = Key)).forEach { other ->
            val error = runCatching { Profile("p0", ServerKind.Audiobookshelf, other) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf(ServerKind.BookOrbit, ServerKind.Komga, ServerKind.Kavita).forEach { kind ->
            val error = runCatching { Profile("p0", kind, rotating) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rotating tokens are read as their sort only, and no other sort as theirs`() {
        val codec = ProfileCodec(FakeTokenCipher())
        val rotating = codec.encode(session.copy(credential = Rotating))

        assertThat(codec.decode(rotating, CredentialFormat.SessionTokens)).isNull()
        assertThat(codec.decode(rotating.copy(sessionId = 42), CredentialFormat.SessionTokens)).isNull()
        assertThat(codec.decode(rotating, CredentialFormat.ApiKey)).isNull()
        assertThat(codec.decode(codec.encode(session), CredentialFormat.RotatingTokens)).isNull()
        assertThat(codec.decode(codec.encode(session.copy(credential = Key)), CredentialFormat.RotatingTokens)).isNull()
    }

    @Test
    fun `rotating tokens never print`() {
        val printed = Rotating.toString()

        assertThat(printed).doesNotContain(Rotating.tokens.accessToken)
        assertThat(printed).doesNotContain(Rotating.tokens.refreshToken)
        assertThat(printed).contains(Rotating.signInId)
    }

    @Test
    fun `a keyed entry of a kind this version does not know stays unread and as it was written`() = runTest {
        val dataStore = dataStore()
        val key = JsonPrimitive(FakeTokenCipher().encrypt("""{"key":"secret","keyId":"0K3Y1D"}"""))
        val keyed = """{"id":"k1","kind":"$KindOfLater","session":{"serverUrl":""" +
            """"https://comics.example.com",""" +
            """"user":{"id":"0B5Y2S1Z","username":"ann@example.com","name":"ann@example.com"},""" +
            """"encryptedTokens":$key},"dataFormat":2}"""
        dataStore.edit { it[RegistryKeys.Profiles] = RegistryOfBefore.replace("}],", "},$keyed],") }
        val registry = loaded(dataStore)

        registry.save(session.copy(serverUrl = "https://other.example.com"))

        assertThat(registry.profile("k1")).isNull()
        assertThat(registry.listedIds()).containsExactly("ann", "k1", "p0")
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(stored(dataStore)).contains(keyed)
    }

    /**
     * Decrypts as [FakeTokenCipher] does, except that every attempt on [text] after the first finds the keystore
     * not answering, as it may for a moment.
     */
    private class SilentAfterFirstTry(private val text: String) : TokenCipher {
        private val fake = FakeTokenCipher()
        var tries = 0
            private set

        override fun encrypt(plainText: String) = fake.encrypt(plainText)

        override fun decrypt(cipherText: String): String? = if (failing(cipherText)) null else fake.decrypt(cipherText)

        override fun decryptOrThrow(cipherText: String): String? {
            if (failing(cipherText)) throw IOException("The keystore failed")
            return fake.decryptOrThrow(cipherText)
        }

        private fun failing(cipherText: String): Boolean {
            if (cipherText != text) return false
            tries++
            return tries > 1
        }
    }

    private companion object {
        val Key = Credential.ApiKey(key = "0123456789abcdef-secret", keyId = "0K3Y1D")

        /** Audiobookshelf's tokens, named by the sign-in the app drew. */
        val Rotating = Credential.RotatingTokens(signInId = "8c1f2b9e-4d7a-4f0e-b6a1-3e5d9c2f7a18", Sample.tokens)

        /** The name of a kind of server no version knows yet: what a later version might write. */
        const val KindOfLater = "Calibre"

        /** A key, encrypted, as a BookOrbit entry should never hold it. */
        val MismatchedKey = JsonPrimitive(FakeTokenCipher().encrypt("""{"key":"secret","keyId":"0K3Y1D"}"""))

        /** A BookOrbit entry of Mia's account holding [MismatchedKey]. */
        val Mismatched = """{"id":"m1","kind":"BookOrbit","session":{"serverUrl":"https://books.example.com",""" +
            """"sessionId":7,"user":{"id":"9","username":"mia","name":"Mia"},"encryptedTokens":$MismatchedKey},""" +
            """"dataFormat":2}"""

        /** The tokens of [Sample.session], encrypted by [FakeTokenCipher] as the version before stored them. */
        val TokensOfBefore = JsonPrimitive(
            FakeTokenCipher().encrypt(
                """{"accessToken":"access-1","accessTokenExpiresAt":"2026-11-13T08:15:00Z",""" +
                    """"refreshToken":"refresh-1","refreshTokenExpiresAt":"2026-11-20T08:00:00Z"}""",
            ),
        )

        /** What the version before wrote for [Sample.session], current, of the current data format. */
        val RegistryOfBefore = """{"profiles":[{"id":"ann","kind":"BookOrbit","session":""" +
            """{"serverUrl":"https://books.example.com","sessionId":42,""" +
            """"user":{"id":"1","username":"reader","name":"Ann Reader","email":"ann@example.com"},""" +
            """"encryptedTokens":$TokensOfBefore},"dataFormat":2}],"current":"ann"}"""

        /**
         * What the version before wrote for a profile that needs to sign in again, whose account may not download
         * and has a picture, current, beside one being removed whose data is of the old format.
         */
        val OtherRegistryOfBefore = """{"profiles":[{"id":"bob","kind":"BookOrbit","session":""" +
            """{"serverUrl":"https://books.example.com","sessionId":7,"user":{"id":"2","username":"bob",""" +
            """"name":"Bob Other","canDownload":false,"avatarUrl":"https://books.example.com/avatar/2"},""" +
            """"encryptedTokens":$TokensOfBefore},"signInNeeded":true,"dataFormat":2},""" +
            """{"id":"cid","kind":"BookOrbit","session":{"serverUrl":"https://old.example.com","sessionId":3,""" +
            """"user":{"id":"3","username":"cid","name":"Cid","email":"cid@example.com"},""" +
            """"encryptedTokens":$TokensOfBefore},"state":"removing","dataFormat":1}],"current":"bob"}"""
    }
}
