package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.assumeFoldersCanBeLocked
import app.tfdn.lirelle.core.library.di.LibraryProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.OldDataFormat
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private const val BookSpeed = 1.5f

/** The folders of the profile [id] on this device. */
private fun Context.foldersOf(id: String) =
    ProfileFolders(File(filesDir, "profiles/$id"), File(cacheDir, "profiles/$id"))

/** Where the device keeps the picture of the profile [id]'s account. */
private fun Context.pictureOf(id: String) = File(filesDir, "avatars/$id")

/** The accounts of [TwoListedProfilesApplication], by profile. */
private val accountOf = mapOf(FirstProfile to 1L, SecondProfile to 2L)

/**
 * The downloads as this version writes them: the audiobook kept whole, its file named by its asset and by no
 * address, where the old format named the server's.
 */
private val CurrentDownloads = mapOf(
    "downloads" to """{"books":[{"bookId":"$OldBook","formats":[{"format":"Audiobook",""" +
        """"state":{"name":"Complete","receivedBytes":1},"files":[{"name":"a1",""" +
        """"source":{"type":"AudioAsset","assetId":"a1"},"version":"v1","expectedBytes":1,"resumable":true,""" +
        """"kind":"Audio"}],"version":"v1","askedAt":"1970-01-01T00:00:00Z"}],""" +
        """"downloadedAt":"1970-01-01T00:00:00Z"}]}""",
)

/**
 * The reading stores of a profile of the current format: the same values, the file and the book named by their
 * ids' text.
 */
private val CurrentReading = OldValues.reading(owner()).mapValues { (_, value) ->
    value.replace(""""fileId":$OldFile""", """"fileId":"$OldFile"""")
        .replace(""""bookId":$OldBook""", """"bookId":"$OldBook"""")
}

/**
 * What a profile that was used holds in its folders, in several of its stores and files, written as this
 * version's stores read them, but for the [reading] and the [downloads], of the format the profile is of; and its
 * account's picture, which the device keeps beside the folders, with the address it was fetched from.
 */
private suspend fun Context.useProfile(
    id: String,
    folders: ProfileFolders = foldersOf(id),
    reading: Map<String, String> = OldValues.reading(owner()),
    downloads: Map<String, String> = OldValues.downloads(owner()),
) {
    suspend fun preferences(name: String, values: Map<String, String>) = folders.dataStore(name).opened { store ->
        store.edit { stored -> values.forEach { (key, value) -> stored[stringPreferencesKey(key)] = value } }
    }
    preferences("reading", reading)
    preferences("downloads", downloads)
    preferences("searches", mapOf("recent_searches" to """["mars","dune"]"""))
    preferences("book_speeds", mapOf("book_speeds" to """{${OldValues.Speeds}}"""))
    folders.dataStore("last_book").opened { store ->
        store.edit {
            it[stringPreferencesKey("book")] = OldBookId.value
            it[stringPreferencesKey("title")] = "Mars"
        }
    }
    File(folders.files, "downloads/$OldBook/audiobook/a1").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(1))
    File(folders.cache, "covers/cover").apply { parentFile?.mkdirs() }.writeText("cover")
    pictureOf(id).apply { parentFile?.mkdirs() }.writeText("picture")
    File(pictureOf(id).path + ".for").writeText(pictureUrlOf(accountOf.getValue(id)))
}

/** The device's settings of the reader, as the user left them. */
private suspend fun Context.setUpTheReader() =
    writePreferences(this, "reader_settings", OldValues.untouched.getValue("reader_settings"))

/**
 * The two profiles of [TwoListedProfilesApplication], used, in the registry the version before this one wrote:
 * their account ids numbers, and no format of their data.
 */
internal class OldFormatProfilesApplication : TwoListedProfilesApplication() {
    override fun formatOf(id: String): Int? = null

    override val pictured = true

    override suspend fun prepare() {
        useProfile(FirstProfile)
        useProfile(SecondProfile)
        setUpTheReader()
    }
}

/** The same, of the current format: written by this version. */
internal class CurrentFormatProfilesApplication : TwoListedProfilesApplication() {
    override val pictured = true

    override suspend fun prepare() {
        useProfile(FirstProfile, reading = CurrentReading, downloads = CurrentDownloads)
        useProfile(SecondProfile, reading = CurrentReading, downloads = CurrentDownloads)
    }
}

/**
 * The current profile of the old format, whose folders an earlier start had set aside when it was interrupted,
 * before it could write the format.
 */
internal class InterruptedResetApplication : TwoListedProfilesApplication() {
    override fun formatOf(id: String): Int? = null

    override val pictured = true

    override suspend fun prepare() {
        val aside = "profiles/old-data-$SecondProfile"
        useProfile(SecondProfile, ProfileFolders(File(filesDir, aside), File(cacheDir, aside)))
    }
}

/**
 * The current profile of the old format, and nothing can be renamed beside it: its folders cannot be set
 * aside. The profile used before it is of the current format.
 */
internal class LockedResetApplication : TwoListedProfilesApplication() {
    override fun formatOf(id: String): Int? = if (id == SecondProfile) null else CurrentDataFormat

    override val pictured = true

    override suspend fun prepare() {
        useProfile(FirstProfile)
        useProfile(SecondProfile)
        File(filesDir, "profiles").setWritable(false)
    }
}

/**
 * The real application started on profiles whose data is of the format before the app named things by ids of
 * its own: each starts again from empty folders once, when it is opened, and keeps its account, its sign-in and
 * its picture; the device keeps its settings.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileDataFormatTest {

    private val application: LirelleApplication get() = ApplicationProvider.getApplicationContext()
    private val device by lazy { TestDevice(application) }
    private val store get() = device.data.profileStore()

    @After
    fun leaveNothingBehind() {
        File(application.filesDir, "profiles").setWritable(true)
        removeFakeAndroidKeystore()
    }

    /** What the device holds in the folders of the profile [id]. */
    private fun held(id: String): List<File> = application.foldersOf(id).let { listOf(it.files, it.cache) }
        .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }

    private fun setAside(): List<String> = device.profileFolders().map { it.name }.filter { it.startsWith("old-") }

    /** The profile [id] is open with nothing of what it held, and of the current format. */
    private suspend fun assertStartedAgain(open: GraphView, id: String) {
        assertWithMessage("format").that(store.profile(id)?.dataFormat).isEqualTo(CurrentDataFormat)
        val reading = open.profile<ReaderCoreProfileEntry>()
        assertWithMessage("position").that(reading.readingPositionStore().get(OldFileId)).isNull()
        assertWithMessage("positions to send").that(reading.readingOutbox().pending().positions).isEmpty()
        assertWithMessage("searches").that(open.profile<LibraryProfileEntry>().recentSearches().entries.first())
            .isEmpty()
        val kept = open.profile<ProfileDataOnDevice>()
        assertWithMessage("downloads").that(kept.downloadRegistry().books.first()).isEmpty()
        assertWithMessage("speed").that(kept.playerSettingsStore().speedOf(OldBookId)).isNull()
        assertWithMessage("last book").that(kept.lastBookStore().get()).isNull()
        assertWithMessage("files").that(held(id).map { it.name }).containsNoneOf("a1", "cover")
        assertWithMessage("picture").that(application.pictureOf(id).exists()).isTrue()
        until("what was set aside is deleted") { setAside().isEmpty() }
    }

    @Test
    @Config(application = OldFormatProfilesApplication::class)
    fun `a profile of the old format opens empty, of the new format, with its picture and the device's settings`() =
        runBlocking<Unit> {
            val open = device.shows(SecondProfile)

            assertStartedAgain(open, SecondProfile)
            assertThat(store.current.value?.session?.user?.id).isEqualTo("2")
            assertThat(device.data.readerSettings().settings.first().theme).isEqualTo(ReaderTheme.Sepia)
            // The profile that sleeps is left as it is until it is opened.
            assertThat(store.profile(FirstProfile)?.dataFormat).isEqualTo(OldDataFormat)
            assertThat(held(FirstProfile).map { it.name }).containsAtLeast("a1", "cover")
        }

    @Test
    @Config(application = OldFormatProfilesApplication::class)
    fun `a switch to a sleeping profile of the old format empties it before it opens`() = runBlocking<Unit> {
        device.shows(SecondProfile)

        val open = device.switchesTo(FirstProfile)

        assertStartedAgain(open, FirstProfile)
        assertThat(store.profile(SecondProfile)?.dataFormat).isEqualTo(CurrentDataFormat)
    }

    @Test
    @Config(application = CurrentFormatProfilesApplication::class)
    fun `a profile already of the new format is untouched`() = runBlocking<Unit> {
        val open = device.shows(SecondProfile)

        val reading = open.profile<ReaderCoreProfileEntry>()
        assertThat(reading.readingPositionStore().get(OldFileId)?.cfi).isEqualTo(OldPlace)
        assertThat(open.profile<LibraryProfileEntry>().recentSearches().entries.first())
            .containsExactly("mars", "dune").inOrder()
        val kept = open.profile<ProfileDataOnDevice>()
        assertThat(kept.downloadRegistry().books.first().map { it.bookId }).containsExactly(OldBookId)
        assertThat(kept.playerSettingsStore().speedOf(OldBookId)).isEqualTo(BookSpeed)
        assertThat(kept.lastBookStore().get()?.title).isEqualTo("Mars")
        assertThat(held(SecondProfile).map { it.name }).containsAtLeast("a1", "cover")
        assertThat(setAside()).isEmpty()
    }

    @Test
    @Config(application = InterruptedResetApplication::class)
    fun `a start interrupted once the folders were set aside ends the same at the next`() = runBlocking<Unit> {
        val open = device.shows(SecondProfile)

        assertStartedAgain(open, SecondProfile)
    }

    @Test
    @Config(application = LockedResetApplication::class)
    fun `a profile whose folders cannot be set aside opens nothing of it, and the one used before it is shown`() =
        runBlocking<Unit> {
            assumeFoldersCanBeLocked()

            device.shows(FirstProfile)

            assertThat(store.profile(SecondProfile)).isNull()
            assertThat(device.listed()).containsExactly(FirstProfile, SecondProfile)
            assertThat(held(SecondProfile).map { it.name }).containsAtLeast("a1", "cover")
            val told = ShadowLog.getLogsForTag("Profiles").filter { "could not be set aside" in it.msg }
            assertThat(told).hasSize(1)
            assertThat(told.single().msg).doesNotContain(File.separator)
        }
}
