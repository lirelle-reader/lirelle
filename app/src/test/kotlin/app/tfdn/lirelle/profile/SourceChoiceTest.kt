package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.di.ProfileSourceEntry
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the sources' classes live: only `:app`'s tests may name them, never the app itself. */
private const val BookOrbitSource = "app.tfdn.lirelle.source.bookorbit."
private const val KomgaSource = "app.tfdn.lirelle.source.komga."
private const val KavitaSource = "app.tfdn.lirelle.source.kavita."

/** Every contract a source fulfils for a profile, by its name, as the profile's objects are given it. */
private fun ProfileSourceEntry.contracts(): Map<String, Any> = mapOf(
    "LibraryRepository" to sourceLibrary(),
    "ReaderRepository" to sourceReader(),
    "AudioRepository" to sourceAudio(),
    "AudioBookmarkRepository" to sourceAudioBookmarks(),
    "MarksRepository" to sourceMarks(),
    "ReadAlongRepository" to sourceReadAlong(),
    "HistoryRepository" to sourceHistory(),
    "PageSource" to sourcePages(),
    "DownloadLocations" to sourceDownloadLocations(),
    "DownloadRefusals" to sourceDownloadRefusals(),
    "AccountRepository" to sourceAccount(),
    "SessionAuthorizer" to sourceAuthorizer(),
    "AccountPictures" to sourceAccountPictures(),
    "@LongTransfer Interceptor" to sourceLongTransfer(),
    "SourceCapabilities" to sourceCapabilities,
)

/** The contracts whose implementation is made afresh for each object that takes one, as its source binds them. */
private val MadeEachTime = setOf("DownloadLocations", "DownloadRefusals")

/** The contracts a source answers with a value of a shared type, not with a class of its own: told by their value. */
private val Declarations = setOf("SourceCapabilities")

/** The contracts of [entry] whose implementation is not a class of the source whose package is [source]. */
private fun elsewhere(entry: ProfileSourceEntry, source: String): Map<String, String> =
    (entry.contracts() - Declarations).mapValues { (_, made) -> made.javaClass.name }
        .filterValues { !it.startsWith(source) }

/** A Komga account's session: its key and the account, named by its email. */
private val KomgaSession = Session(
    serverUrl = "https://bd.example.com",
    credential = Credential.ApiKey(key = "4f1c0b6e9d8a4c2f", keyId = "0K3Y"),
    user = UserProfile("0B5Y", "ann@example.com", "ann@example.com", "ann@example.com"),
)

/** A Kavita account's session: its key and the account, named by its username. */
private val KavitaSession = Session(
    serverUrl = "https://kavita.example.com",
    credential = Credential.ApiKey(key = "k4v1t4d3v1c3k3y0123456789abcdefgh", keyId = "42"),
    user = UserProfile("7", "ann", "ann", "ann@example.com"),
)

/**
 * What Kavita declares once a profile can connect and no more (spec 049, §6): nothing, but the one sort its empty
 * list of books is in, and a Library that opens on its series.
 */
private val KavitaCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = false,
    authors = false,
    history = false,
    homeRows = emptySet(),
    shelfKinds = emptySet(),
    searchKinds = emptySet(),
    bookSorts = setOf(BookSort.Added),
    statusFilters = emptySet(),
    formatFilters = emptySet(),
    libraryStart = LibraryStart.Series,
)

/**
 * The source of a profile is chosen by the kind of server its registry entry records: on the application's real
 * graph, a profile of kind BookOrbit is given BookOrbit's implementation of every contract, one of kind Komga
 * Komga's, one of kind Kavita Kavita's, each with the scope the source gave it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class SourceChoiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val profiles = TwoProfiles(device)

    @After
    fun close() {
        runBlocking { profiles.closeAll() }
        removeFakeAndroidKeystore()
    }

    private suspend fun current(): GraphView {
        val view = GraphView(awaited("the current profile is open") { device.data.openProfiles().current.value })
        assertWithMessage("the profile's kind").that(device.data.profileStore().profile(view.id)?.kind)
            .isEqualTo(ServerKind.BookOrbit)
        return view
    }

    /** A Komga profile, opened beside the BookOrbit one the app shows. */
    private suspend fun komga(): GraphView {
        current()
        return profiles.openUnlisted(Profile("komga", ServerKind.Komga, KomgaSession))
    }

    /** A Kavita profile, opened beside the BookOrbit one the app shows. */
    private suspend fun kavita(): GraphView {
        current()
        return profiles.openUnlisted(Profile("kavita", ServerKind.Kavita, KavitaSession))
    }

    @Test
    fun `a profile of kind BookOrbit gets BookOrbit's implementation of every contract`(): Unit = runBlocking {
        val entry = current().profile<ProfileSourceEntry>()

        assertThat(entry.contracts()).hasSize(ProfileSourceEntry::class.java.declaredMethods.size)
        assertWithMessage("contracts not fulfilled by the BookOrbit source")
            .that(elsewhere(entry, BookOrbitSource)).isEmpty()
    }

    @Test
    fun `a profile of kind Komga gets Komga's implementation of every contract`(): Unit = runBlocking {
        val entry = komga().profile<ProfileSourceEntry>()

        assertThat(entry.contracts()).hasSize(ProfileSourceEntry::class.java.declaredMethods.size)
        assertWithMessage("contracts not fulfilled by the Komga source").that(elsewhere(entry, KomgaSource)).isEmpty()
    }

    @Test
    fun `a profile of kind Kavita gets Kavita's implementation of every contract`(): Unit = runBlocking {
        val entry = kavita().profile<ProfileSourceEntry>()

        assertThat(entry.contracts()).hasSize(ProfileSourceEntry::class.java.declaredMethods.size)
        assertWithMessage("contracts not fulfilled by the Kavita source").that(elsewhere(entry, KavitaSource)).isEmpty()
    }

    @Test
    fun `a profile of kind BookOrbit can do everything BookOrbit declares`(): Unit = runBlocking {
        val capabilities = current().profile<ProfileSourceEntry>().sourceCapabilities

        assertThat(capabilities).isEqualTo(SourceCapabilities.Everything)
    }

    @Test
    fun `a profile of kind Komga can do what Komga declares`(): Unit = runBlocking {
        assertThat(komga().profile<ProfileSourceEntry>().sourceCapabilities).isEqualTo(KomgaLikeCapabilities)
    }

    @Test
    fun `a profile of kind Kavita can do what Kavita declares`(): Unit = runBlocking {
        assertThat(kavita().profile<ProfileSourceEntry>().sourceCapabilities).isEqualTo(KavitaCapabilities)
    }

    @Test
    fun `each implementation keeps the scope its source gave it`(): Unit = runBlocking {
        listOf(current(), komga(), kavita()).forEach { view ->
            val entry = view.profile<ProfileSourceEntry>()
            val once = entry.contracts()
            val again = entry.contracts()

            val same = once.filter { (name, made) -> made === again[name] }.keys
            assertWithMessage(view.id).that(same).containsExactlyElementsIn(once.keys - MadeEachTime)
        }
    }
}
