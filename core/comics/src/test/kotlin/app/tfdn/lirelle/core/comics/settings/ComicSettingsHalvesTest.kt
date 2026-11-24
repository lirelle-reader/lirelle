package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The two halves of the comics settings: how comics are shown is the device's, the directions a profile's. */
class ComicSettingsHalvesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val series = DirectionScope.Series(SeriesId("7"))
    private val book = DirectionScope.Book(BookId("7"))

    private val device by lazy { file("comics_settings") }
    private val profile by lazy { file("comic_directions") }

    private fun file(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }

    /** The whole store, from the device's file and a profile's. */
    private fun store(profile: DataStore<Preferences> = this.profile) =
        DataStoreComicSettingsStore(DataStoreComicDeviceSettings(device), profile)

    private suspend fun DataStore<Preferences>.stored(): Map<String, Any> =
        data.first().asMap().mapKeys { it.key.name }

    @After
    fun close() = scope.cancel()

    @Test
    fun `a direction is written to the profile's file, not to the device's`() = runBlocking<Unit> {
        val store = store()

        store.setDirection(series, Direction.RightToLeft)
        store.setDirection(book, Direction.RightToLeft)

        assertThat(profile.stored())
            .containsExactly("direction.series.7", "RightToLeft", "direction.book.7", "RightToLeft")
        assertThat(device.stored()).isEmpty()
    }

    @Test
    fun `how comics are shown is written to the device's file, and nothing of it to the profile's`() =
        runBlocking<Unit> {
            val store = store()

            store.setFit(Fit.Width)
            store.setTwoPages(TwoPages.Never)
            store.setBackground(Background.White)

            assertThat(device.stored()).containsExactly("fit", "Width", "twoPages", "Never", "background", "White")
            assertThat(profile.stored()).isEmpty()
        }

    @Test
    fun `two profiles on one device share how comics are shown, and not their directions`() = runBlocking<Unit> {
        val one = store()
        val other = store(profile = file("comic_directions_of_another"))

        one.setBackground(Background.Grey)
        one.setDirection(series, Direction.RightToLeft)

        assertThat(other.settings(series).first()).isEqualTo(ComicSettings(background = Background.Grey))
        assertThat(other.shared.first()).isEqualTo(ComicSettings(background = Background.Grey))

        other.setFit(Fit.Width)
        other.setDirection(book, Direction.RightToLeft)

        assertThat(one.settings(series).first())
            .isEqualTo(ComicSettings(Fit.Width, TwoPages.Automatic, Direction.RightToLeft, Background.Grey))
        assertThat(one.settings(book).first().direction).isEqualTo(Direction.LeftToRight)
    }

    /** What an installation from before the split left there is only ignored: the migration moves it first. */
    @Test
    fun `directions left in the device's file are not read as the profile's`() = runBlocking<Unit> {
        device.edit {
            it[stringPreferencesKey("background")] = "White"
            it[stringPreferencesKey("direction.series.7")] = "RightToLeft"
            it[stringPreferencesKey("direction.book.7")] = "RightToLeft"
        }
        val store = store()

        assertThat(store.settings(series).first()).isEqualTo(ComicSettings(background = Background.White))
        assertThat(store.settings(book).first()).isEqualTo(ComicSettings(background = Background.White))
    }

    /**
     * The reader's view model outlives its profile by a moment: a direction chosen then meets a file whose
     * profile is closed, and whose folder may be deleted already.
     */
    @Test
    fun `a direction set once the profile closed is dropped quietly, and its folder is not made again`() =
        runBlocking<Unit> {
            val folders = ProfileFolders(File(folder.root, "files/p1"), File(folder.root, "cache/p1"))
            val life = CoroutineScope(SupervisorJob())
            val store = DataStoreComicSettingsStore(
                DataStoreComicDeviceSettings(device),
                folders.newDataStore("comic_directions", life),
            )
            store.setDirection(series, Direction.RightToLeft)
            assertThat(folders.dataStore("comic_directions").isFile).isTrue()

            life.coroutineContext.job.cancelAndJoin()
            assertThat(folders.files.deleteRecursively()).isTrue()

            val failures = mutableListOf<Throwable>()
            val lasting =
                CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, failure -> failures += failure })
            withTimeout(10.seconds) {
                lasting.launch { store.setDirection(book, Direction.RightToLeft) }.join()
                lasting.launch { store.settings(series).first() }.join()
                lasting.launch { store.setBackground(Background.White) }.join()
            }

            assertThat(failures).isEmpty()
            assertThat(folders.files.exists()).isFalse()
            // The device's half is no profile's: it is still written.
            assertThat(device.stored()).containsExactly("background", "White")
        }
}
