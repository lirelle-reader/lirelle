package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A store whose file cannot be read or written. */
internal val BrokenDataStore = object : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("disk") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("disk")
}

class ComicSettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val series = DirectionScope.Series(SeriesId("7"))
    private val book = DirectionScope.Book(BookId("7"))

    private val device: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "comics_settings.preferences_pb") }
    }
    private val profile: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "comic_directions.preferences_pb") }
    }

    /** The whole store, from the device's file and a profile's. */
    private fun store() = DataStoreComicSettingsStore(DataStoreComicDeviceSettings(device), profile)

    @After
    fun close() = scope.cancel()

    @Test
    fun `a new store holds the defaults`() = runBlocking<Unit> {
        val settings = store().settings(series).first()

        assertThat(settings).isEqualTo(ComicSettings())
        assertThat(settings)
            .isEqualTo(ComicSettings(Fit.Page, TwoPages.Automatic, Direction.LeftToRight, Background.Black))
    }

    @Test
    fun `the fit, the two pages and the background are the device's`() = runBlocking<Unit> {
        val store = store()

        store.setFit(Fit.Width)
        store.setTwoPages(TwoPages.Always)
        store.setBackground(Background.Grey)

        val expected = ComicSettings(fit = Fit.Width, twoPages = TwoPages.Always, background = Background.Grey)
        assertThat(store.settings(series).first()).isEqualTo(expected)
        assertThat(store.settings(DirectionScope.Series(SeriesId("8"))).first()).isEqualTo(expected)
        assertThat(store.settings(book).first()).isEqualTo(expected)
    }

    @Test
    fun `the direction is the series's`() = runBlocking<Unit> {
        val store = store()

        store.setDirection(series, Direction.RightToLeft)

        assertThat(store.settings(series).first().direction).isEqualTo(Direction.RightToLeft)
        assertThat(store.settings(DirectionScope.Series(SeriesId("8"))).first().direction)
            .isEqualTo(Direction.LeftToRight)
    }

    @Test
    fun `a vertical direction is kept for a series, and for an album alone`() = runBlocking<Unit> {
        val store = store()

        store.setDirection(series, Direction.Vertical)

        assertThat(store.settings(series).first().direction).isEqualTo(Direction.Vertical)
        assertThat(store.settings(book).first().direction).isEqualTo(Direction.LeftToRight)

        store.setDirection(book, Direction.Vertical)

        assertThat(store.settings(book).first().direction).isEqualTo(Direction.Vertical)
    }

    @Test
    fun `the direction given by default is the series's until the user chooses one, and is not kept`() =
        runBlocking<Unit> {
            val store = store()

            assertThat(store.settings(series, default = Direction.RightToLeft).first().direction)
                .isEqualTo(Direction.RightToLeft)
            assertThat(store.settings(series).first().direction).isEqualTo(Direction.LeftToRight)

            store.setDirection(series, Direction.LeftToRight)

            assertThat(store.settings(series, default = Direction.RightToLeft).first().direction)
                .isEqualTo(Direction.LeftToRight)
        }

    @Test
    fun `a book and a series of the same id do not share a direction`() = runBlocking<Unit> {
        val store = store()

        store.setDirection(book, Direction.RightToLeft)

        assertThat(store.settings(book).first().direction).isEqualTo(Direction.RightToLeft)
        assertThat(store.settings(series).first().direction).isEqualTo(Direction.LeftToRight)

        store.setDirection(series, Direction.RightToLeft)
        store.setDirection(book, Direction.LeftToRight)

        assertThat(store.settings(book).first().direction).isEqualTo(Direction.LeftToRight)
        assertThat(store.settings(series).first().direction).isEqualTo(Direction.RightToLeft)
    }

    @Test
    fun `every setting survives a new instance`() = runBlocking<Unit> {
        store().run {
            setFit(Fit.Width)
            setTwoPages(TwoPages.Never)
            setBackground(Background.White)
            setDirection(series, Direction.RightToLeft)
        }

        assertThat(store().settings(series).first())
            .isEqualTo(ComicSettings(Fit.Width, TwoPages.Never, Direction.RightToLeft, Background.White))
    }

    @Test
    fun `a name the app does not know reads as the default`() = runBlocking<Unit> {
        store().run {
            setFit(Fit.Width)
            setTwoPages(TwoPages.Always)
            setBackground(Background.White)
            setDirection(series, Direction.RightToLeft)
        }
        listOf(device to 3, profile to 1).forEach { (file, kept) ->
            file.edit { stored ->
                val names = stored.asMap().keys.map { it.name }
                assertThat(names).hasSize(kept)
                names.forEach { stored[stringPreferencesKey(it)] = "Sideways" }
            }
        }

        assertThat(store().settings(series).first()).isEqualTo(ComicSettings())
    }

    @Test
    fun `a store that cannot be read or written holds the defaults`() = runBlocking<Unit> {
        val store = DataStoreComicSettingsStore(DataStoreComicDeviceSettings(BrokenDataStore), BrokenDataStore)

        store.setFit(Fit.Width)
        store.setDirection(series, Direction.RightToLeft)

        assertThat(store.settings(series).first()).isEqualTo(ComicSettings())
    }
}
