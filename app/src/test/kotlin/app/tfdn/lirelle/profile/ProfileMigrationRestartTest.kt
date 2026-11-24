package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.assumeFoldersCanBeLocked
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.ProfileRoots
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestServer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration across starts of the application, on real files: each start reads the registry from its file
 * again, as a process does, so that what an interrupted or failed migration left is what the next one finds.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileMigrationRestartTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    @get:Rule
    val folder = TemporaryFolder()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val cipher by lazy { KeystoreTokenCipher() }
    private val signedOut = SessionState.SignedOut(TestServer, reason = null)

    /** A device with the old installation on it, written by [install]. */
    private suspend fun device(install: suspend OldInstallation.() -> Unit = { write() }): OldDevice =
        oldDevice(base, folder.newFolder(), cipher, install)

    /** One start: the migration runs, and the process ends. @return the parts it did, in order. */
    private suspend fun OldDevice.start(interruptAfter: String? = null): List<String> = start(cipher, interruptAfter)

    /** The files that are the device's alone, by their path, as the old installation wrote them. */
    private val untouched = OldValues.untouched.entries.associate { (name, values) ->
        "files/datastore/$name.preferences_pb" to values.toSortedMap().toString()
    }

    private fun OldDevice.profile() = ProfileRoots(filesDir, cacheDir).of("p0")

    private fun OldDevice.old(name: String) = preferencesDataStoreFile(name)

    @Test
    fun `everything of the account is in the profile's folders, and the device's settings stay`(): Unit =
        runBlocking {
            val device = device()

            device.start()

            val moved = device.snapshot(cipher)
            assertThat(moved.registry).isEqualTo(
                RegistryFacts(SessionState.SignedIn(OldSession), "p0", setOf("p0"), emptyList(), emptyList()),
            )
            val files = moved.files.filterValues { it != "<folder>" }
            assertThat(files).containsExactlyEntriesIn(
                mapOf(
                    "files/profiles/p0/datastore/reading.preferences_pb" to
                        (OldValues.reading(owner()) - "marks").toSortedMap().toString(),
                    "files/profiles/p0/datastore/listening.preferences_pb" to
                        OldValues.listening(owner()).toSortedMap().toString(),
                    "files/profiles/p0/datastore/downloads.preferences_pb" to
                        OldValues.downloads(owner()).toSortedMap().toString(),
                    "files/profiles/p0/datastore/searches.preferences_pb" to
                        mapOf("recent_searches" to """["mars","dune"]""").toString(),
                    "files/profiles/p0/datastore/comic_directions.preferences_pb" to
                        (OldValues.comics - setOf("fit", "twoPages", "background")).toSortedMap().toString(),
                    "files/profiles/p0/datastore/book_speeds.preferences_pb" to
                        mapOf("book_speeds" to "{${OldValues.Speeds}}").toString(),
                    // Neither the server nor the user the old store kept beside it: the file is the profile's.
                    "files/profiles/p0/datastore/last_book.preferences_pb" to sortedMapOf(
                        "book" to OldBook,
                        "title" to "Mars",
                        "cover" to OldCover,
                    ).toString(),
                    "files/profiles/p0/last_book.kept" to "",
                    "files/profiles/p0/downloads/$OldBook/audiobook/a1" to String(ByteArray(OldAudioBytes)),
                    "files/profiles/p0/downloads/$OldBook/detail.json" to "{}",
                    "files/profiles/p0/readalong/$OldFile-none.map.gz" to "map",
                    "cache/profiles/p0/covers/cover" to "cover",
                    "cache/profiles/p0/reader/book/chapter" to "chapter",
                    "cache/profiles/p0/comics/31-v1/0" to "page",
                    // Out of the way, under a name that is no profile's: the start's sweep deletes it.
                    "cache/profiles/old-audio/0/stream.exo" to "audio",
                    "files/datastore/library.preferences_pb" to
                        mapOf("view.books" to OldValues.library.getValue("view.books")).toString(),
                    "files/datastore/comics_settings.preferences_pb" to
                        sortedMapOf("fit" to "Width", "twoPages" to "Always", "background" to "White").toString(),
                    "files/datastore/player_settings.preferences_pb" to
                        mapOf("player_settings" to OldValues.PlayerDevice).toString(),
                ) + untouched,
            )
            // Neither the old last book, nor the index of the old audio cache: it named what was listened to.
            assertThat(moved.lastBook).isEmpty()
            assertThat(moved.databases).isEmpty()
        }

    @Test
    fun `an installation that was signed out has no profile, keeps its server and its device settings`(): Unit =
        runBlocking {
            val device = device { write(signedIn = false) }

            device.start()

            val left = device.snapshot(cipher)
            assertThat(left.registry).isEqualTo(
                RegistryFacts(signedOut, current = null, emptySet(), moving = emptyList(), removing = emptyList()),
            )
            // What the account that left kept is nobody's: the files, and its part of the device's files. Its
            // folders are not deleted by the migration, which runs before the first screen: each is out of
            // the way under a name that is no profile's, and the start's sweep deletes it.
            assertThat(left.files.filterValues { it != "<folder>" }).containsExactlyEntriesIn(
                mapOf(
                    "files/datastore/library.preferences_pb" to
                        mapOf("view.books" to OldValues.library.getValue("view.books")).toString(),
                    "files/datastore/comics_settings.preferences_pb" to
                        sortedMapOf("fit" to "Width", "twoPages" to "Always", "background" to "White").toString(),
                    "files/datastore/player_settings.preferences_pb" to
                        mapOf("player_settings" to OldValues.PlayerDevice).toString(),
                    "files/profiles/old-files/$OldBook/audiobook/a1" to String(ByteArray(OldAudioBytes)),
                    "files/profiles/old-files/$OldBook/detail.json" to "{}",
                    "files/profiles/old-files-2/$OldFile-none.map.gz" to "map",
                    "cache/profiles/old-cache/cover" to "cover",
                    "cache/profiles/old-cache-2/book/chapter" to "chapter",
                    "cache/profiles/old-cache-3/31-v1/0" to "page",
                    "cache/profiles/old-audio/0/stream.exo" to "audio",
                ) + untouched,
            )
            assertThat(left.files.keys.filter { it.setAsideOrGone() }).isEmpty()
            assertThat(left.lastBook).isEmpty()
            assertThat(left.databases).isEmpty()
        }

    /** Interrupts a migration after each of its parts in turn, and runs it again: the same as uninterrupted. */
    private suspend fun assertSameWhereverInterrupted(install: suspend OldInstallation.() -> Unit) {
        val whole = device(install)
        val parts = whole.start()
        val expected = whole.snapshot(cipher)
        assertThat(parts.last()).isEqualTo("finished")

        parts.forEach { part ->
            val device = device(install)
            assertWithMessage("parts until '$part'").that(device.start(interruptAfter = part).last()).isEqualTo(part)

            device.start()

            assertWithMessage("interrupted after '$part'").that(device.snapshot(cipher)).isEqualTo(expected)
        }
    }

    @Test
    fun `interrupted after any part and run again, the result is the same as uninterrupted`(): Unit =
        runBlocking {
            assertSameWhereverInterrupted { write() }
        }

    @Test
    fun `interrupted after any part and run again, a signed-out installation ends the same too`(): Unit =
        runBlocking {
            assertSameWhereverInterrupted { write(signedIn = false) }
        }

    @Test
    fun `interrupted twice, at the same part and at a later one, it still ends the same`(): Unit = runBlocking {
        val whole = device()
        val parts = whole.start()
        val expected = whole.snapshot(cipher)
        val device = device()

        device.start(interruptAfter = parts[2])
        device.start(interruptAfter = parts[2])
        device.start(interruptAfter = parts[parts.size - 2])
        device.start()

        assertThat(device.snapshot(cipher)).isEqualTo(expected)
    }

    @Test
    fun `stopped between the last book's write and its mark, the next start ends the same`(): Unit = runBlocking {
        assumeFoldersCanBeLocked()
        val whole = device().also { it.start() }
        val device = device()
        val folder = device.profile().files
        // The mark's file cannot be made: the book is in the profile's store, and the migration stops there.
        val failure = runCatching {
            device.start(cipher) { part -> if (part == "deleted audio index") folder.setWritable(false) }
        }.exceptionOrNull()
        folder.setWritable(true)

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(readPreferences(device.profile().dataStore("last_book"))).containsKey("book")
        assertThat(File(folder, "last_book.kept").exists()).isFalse()
        assertThat(device.getSharedPreferences("player_last_book", Context.MODE_PRIVATE).all).isNotEmpty()

        device.start()

        assertThat(device.snapshot(cipher)).isEqualTo(whole.snapshot(cipher))
    }

    @Test
    fun `a session the old version wrote without an address is the same account, which may download`(): Unit =
        runBlocking {
            val device = device { write(email = null) }

            device.start()

            val user = OldSession.user.copy(email = null)
            assertThat(user.canDownload).isTrue()
            assertThat(device.registryFacts(cipher).state)
                .isEqualTo(SessionState.SignedIn(OldSession.copy(user = user)))
        }

    @Test
    fun `a second run after the migration does nothing`(): Unit = runBlocking {
        val device = device()
        device.start()
        val migrated = device.snapshot(cipher)

        val parts = device.start()

        assertThat(parts).isEmpty()
        assertThat(device.snapshot(cipher)).isEqualTo(migrated)
    }

    @Test
    fun `an installation that never was without profiles is left alone`(): Unit = runBlocking {
        val device = OldDevice(base, folder.newFolder())

        val parts = device.start()

        assertThat(parts).isEmpty()
        assertThat(device.snapshot(cipher).files.filterValues { it != "<folder>" }).isEmpty()
    }

    @Test
    fun `what another account left in the stores is emptied, and its last book is not taken`(): Unit =
        runBlocking {
            val device = device { write(owner = owner(user = OldUser + 1), lastBookUser = OldUser + 1) }

            device.start()

            val files = device.snapshot(cipher).files
            listOf("reading", "listening", "downloads").forEach { store ->
                assertWithMessage(store).that(files["files/profiles/p0/datastore/$store.preferences_pb"])
                    .isEqualTo("{}")
            }
            assertThat(files.keys.filter { "last_book" in it }).isEmpty()
            // What says nothing of its owner is the account's all the same: it was signed in with it.
            assertThat(files).containsKey("files/profiles/p0/datastore/searches.preferences_pb")
            assertThat(files).containsKey("files/profiles/p0/datastore/book_speeds.preferences_pb")
        }

    @Test
    fun `the same user of another server is another account`(): Unit = runBlocking {
        val device = device { write(owner = owner(server = "https://other.example.com")) }

        device.start()

        assertThat(device.snapshot(cipher).files["files/profiles/p0/datastore/reading.preferences_pb"])
            .isEqualTo("{}")
    }

    @Test
    fun `a value that names no owner is nobody's, and one that is not a store's is left`(): Unit = runBlocking {
        val device = device {
            write()
            writePreferences(context, "reading", mapOf("positions" to """{"positions":[]}""", "other" to "text"))
        }

        device.start()

        assertThat(readPreferences(device.profile().dataStore("reading")).keys)
            .containsExactly("narrations", "outbox", "marks2", "other")
    }

    @Test
    fun `a value whose owner cannot be understood is nobody's too`(): Unit = runBlocking {
        val device = device {
            write()
            writePreferences(
                context,
                "reading",
                mapOf(
                    "positions" to """{"owner":"somebody","positions":[]}""",
                    "narrations" to """{"owner":{"serverUrl":"$TestServer"},"narrations":[]}""",
                    "outbox" to """{"owner":[],"positions":[]}""",
                ),
            )
        }

        device.start()

        // Kept, the stores would read them as the profile's own once they stop looking at the owner.
        assertThat(readPreferences(device.profile().dataStore("reading")).keys).containsExactly("marks2")
    }

    @Test
    fun `a move that fails leaves the profile being moved in, and the next start ends the migration`(): Unit =
        runBlocking {
            val whole = device().also { it.start() }
            val device = device()
            // Something is where the downloads go, which the system will not rename a folder over.
            val blocker = File(device.profile().files, "downloads/in the way").apply { parentFile?.mkdirs() }
            blocker.writeText("x")
            val start = MigrationStart(device, cipher)

            val failure = runCatching { start.migration.run() }.exceptionOrNull()
            start.end()

            assertThat(failure).isInstanceOf(IOException::class.java)
            val from = File(device.filesDir, "downloads")
            assertThat(failure).hasMessageThat().isEqualTo("Could not move $from to ${blocker.parentFile}")
            // Listed, so that its folders are kept; neither current nor ready, so that nothing opens it.
            assertThat(device.registryFacts(cipher)).isEqualTo(
                RegistryFacts(signedOut, current = null, setOf("p0"), moving = listOf("p0"), removing = emptyList()),
            )
            assertThat(device.old("session").exists()).isTrue()
            assertThat(File(from, "$OldBook/audiobook/a1").exists()).isTrue()

            blocker.parentFile?.deleteRecursively()
            device.start()

            assertThat(device.snapshot(cipher)).isEqualTo(whole.snapshot(cipher))
        }

    @Test
    fun `a session whose tokens can no longer be read is nobody's, and what it kept goes`(): Unit = runBlocking {
        val device = device {
            write()
            writePreferences(context, "session", mapOf("session" to """{"serverUrl":"$TestServer"}"""))
        }

        device.start()

        val left = device.snapshot(cipher)
        assertThat(left.registry.state).isEqualTo(SessionState.SignedOut(TestServer, reason = null))
        assertThat(left.files.keys.filter { it.setAsideOrGone() }).isEmpty()
    }
}
