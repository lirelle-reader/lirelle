package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The settings of reading panel by panel: what is the profile's, what is the device's, and their defaults. */
class PanelChoicesTest {

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
    fun `the mode is off by default`() = runBlocking<Unit> {
        assertThat(store().settings(series).first().panels).isFalse()
        assertThat(store().settings(book).first().panels).isFalse()
        assertThat(store().shared.first().panels).isFalse()
    }

    @Test
    fun `set for a series, the mode is on for that series only`() = runBlocking<Unit> {
        val store = store()

        store.setPanels(series, on = true)

        assertThat(store.settings(series).first().panels).isTrue()
        assertThat(store.settings(DirectionScope.Series(SeriesId("8"))).first().panels).isFalse()
        // The device's half says nothing of it.
        assertThat(store.shared.first().panels).isFalse()
    }

    @Test
    fun `a series and an album of the same id do not share the mode`() = runBlocking<Unit> {
        val store = store()

        store.setPanels(series, on = true)

        assertThat(store.settings(book).first().panels).isFalse()

        store.setPanels(book, on = true)
        store.setPanels(series, on = false)

        assertThat(store.settings(book).first().panels).isTrue()
        assertThat(store.settings(series).first().panels).isFalse()
    }

    @Test
    fun `the mode is written to the profile's file, not to the device's, and survives a new instance`() =
        runBlocking<Unit> {
            store().setPanels(series, on = true)
            store().setPanels(book, on = false)

            assertThat(profile.stored()).containsExactly("panels.series.7", true, "panels.book.7", false)
            assertThat(device.stored()).isEmpty()
            assertThat(store().settings(series).first().panels).isTrue()
            // Another profile of the device has its own.
            assertThat(store(profile = file("comic_directions_of_another")).settings(series).first().panels).isFalse()
        }

    @Test
    fun `the mode leaves the direction alone, and the direction the mode`() = runBlocking<Unit> {
        val store = store()

        store.setDirection(series, Direction.RightToLeft)
        store.setPanels(series, on = true)

        assertThat(store.settings(series).first())
            .isEqualTo(ComicSettings(direction = Direction.RightToLeft, panels = true))
    }

    @Test
    fun `whole page first is on by default, and is the device's`() = runBlocking<Unit> {
        val store = store()
        assertThat(store.shared.first().wholePageFirst).isTrue()
        assertThat(store.settings(series).first().wholePageFirst).isTrue()

        store.setWholePageFirst(false)

        assertThat(store.shared.first().wholePageFirst).isFalse()
        assertThat(store.settings(series).first().wholePageFirst).isFalse()
        assertThat(store.settings(book).first().wholePageFirst).isFalse()
        assertThat(device.stored()).containsExactly("panels.whole_first", false)
        assertThat(profile.stored()).isEmpty()
        // Every profile of the device has it.
        val other = store(profile = file("comic_directions_of_another"))
        assertThat(other.settings(series).first().wholePageFirst).isFalse()

        other.setWholePageFirst(true)

        assertThat(store().shared.first().wholePageFirst).isTrue()
    }

    @Test
    fun `the notice is unseen until seen, on the device`() = runBlocking<Unit> {
        val store = store()
        assertThat(store.panelNoticeSeen.first()).isFalse()

        store.seePanelNotice()

        assertThat(store.panelNoticeSeen.first()).isTrue()
        assertThat(device.stored()).containsExactly("panels.notice_seen", true)
        assertThat(profile.stored()).isEmpty()
        assertThat(store(profile = file("comic_directions_of_another")).panelNoticeSeen.first()).isTrue()
    }

    @Test
    fun `an album is told once, in the profile`() = runBlocking<Unit> {
        val store = store()
        assertThat(store.albumTold(BookId("7"))).isFalse()

        store.tellAlbum(BookId("7"))

        assertThat(store.albumTold(BookId("7"))).isTrue()
        assertThat(store.albumTold(BookId("8"))).isFalse()
        assertThat(profile.stored()).containsExactly("panels.told.7", true)
        assertThat(device.stored()).isEmpty()
        // At the next opening too; and it says nothing of the mode of an album of that id.
        assertThat(store().albumTold(BookId("7"))).isTrue()
        assertThat(store().settings(book).first().panels).isFalse()
    }

    @Test
    fun `a store that cannot be read or written holds the defaults`() = runBlocking<Unit> {
        val store = DataStoreComicSettingsStore(DataStoreComicDeviceSettings(BrokenDataStore), BrokenDataStore)

        store.setPanels(series, on = true)
        store.setWholePageFirst(false)
        store.seePanelNotice()
        store.tellAlbum(BookId("7"))

        assertThat(store.settings(series).first()).isEqualTo(ComicSettings())
        assertThat(store.settings(series).first().panels).isFalse()
        assertThat(store.settings(series).first().wholePageFirst).isTrue()
        assertThat(store.panelNoticeSeen.first()).isFalse()
        assertThat(store.albumTold(BookId("7"))).isFalse()
    }
}
