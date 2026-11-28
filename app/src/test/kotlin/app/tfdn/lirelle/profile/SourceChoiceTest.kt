package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.di.ProfileSourceEntry
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
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
private const val AbsSource = "app.tfdn.lirelle.source.audiobookshelf."

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

/** Long after the tests run. */
private val FarAway: Instant = Instant.parse("2100-01-01T00:00:00Z")

/**
 * An Audiobookshelf account's session: its tokens under the sign-in's id, and the account, named by its username. The
 * access token far from expiring: nothing is renewed while the test looks.
 */
private val AbsSession = Session(
    serverUrl = "https://abs.example.com",
    credential = Credential.RotatingTokens(
        signInId = "0b9d6c3e-1f2a-4e5b-8c7d-6a5b4c3d2e1f",
        tokens = Tokens("access", FarAway, "refresh", FarAway),
    ),
    user = UserProfile("5f0e1a8c-6b3d-4c2e-9a71-2d4b8e6f1c90", "ann", "ann", null),
)

/**
 * What Audiobookshelf declares once its profile can be browsed (spec 067, §9): genres, authors, Home's rows, its
 * collections and playlists, Search, the Books list's sorts and filters, a Library that opens on its books and always
 * shows one library; nothing of listening or reading yet.
 */
private val AbsCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = true,
    authors = true,
    history = false,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection, ShelfKind.Playlist),
    searchKinds = SearchKind.entries.toSet(),
    bookSorts = setOf(BookSort.Added, BookSort.Title, BookSort.Author, BookSort.Published),
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = FormatFilter.entries.toSet(),
    libraryStart = LibraryStart.Books,
    libraryAlwaysChosen = true,
)

/**
 * What Kavita declares once its books can be read (spec 050, §9 with §14; spec 051): its library without a Books tab
 * or series counts, a Library that opens on its series, its reading sessions and History, and its text files read
 * only with the account's right to download.
 */
private val KavitaCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = true,
    genres = true,
    authors = true,
    history = true,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.WantToRead, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection),
    searchKinds = setOf(SearchKind.Books, SearchKind.Series, SearchKind.Authors),
    bookSorts = setOf(BookSort.Added, BookSort.Title),
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = setOf(FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics),
    libraryStart = LibraryStart.Series,
    libraryBooks = false,
    seriesCounts = false,
    downloadToRead = true,
)

/**
 * The source of a profile is chosen by the kind of server its registry entry records: on the application's real
 * graph, a profile of kind BookOrbit is given BookOrbit's implementation of every contract, one of kind Komga
 * Komga's, one of kind Kavita Kavita's, one of kind Audiobookshelf Audiobookshelf's, each with the scope the source
 * gave it.
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

    /** An Audiobookshelf profile, opened beside the BookOrbit one the app shows. */
    private suspend fun audiobookshelf(): GraphView {
        current()
        return profiles.openUnlisted(Profile("audiobookshelf", ServerKind.Audiobookshelf, AbsSession))
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
    fun `a profile of kind Audiobookshelf gets Audiobookshelf's implementation of every contract`(): Unit =
        runBlocking {
            val entry = audiobookshelf().profile<ProfileSourceEntry>()

            assertThat(entry.contracts()).hasSize(ProfileSourceEntry::class.java.declaredMethods.size)
            assertWithMessage("contracts not fulfilled by the Audiobookshelf source")
                .that(elsewhere(entry, AbsSource)).isEmpty()
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
    fun `a profile of kind Audiobookshelf can do what Audiobookshelf declares`(): Unit = runBlocking {
        assertThat(audiobookshelf().profile<ProfileSourceEntry>().sourceCapabilities).isEqualTo(AbsCapabilities)
    }

    @Test
    fun `each implementation keeps the scope its source gave it`(): Unit = runBlocking {
        listOf(current(), komga(), kavita(), audiobookshelf()).forEach { view ->
            val entry = view.profile<ProfileSourceEntry>()
            val once = entry.contracts()
            val again = entry.contracts()

            val same = once.filter { (name, made) -> made === again[name] }.keys
            assertWithMessage(view.id).that(same).containsExactlyElementsIn(once.keys - MadeEachTime)
        }
    }
}
