package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
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

/** The settings that belong to no series, as the settings page reads them. */
class ComicSettingsSharedTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "comics_settings.preferences_pb") }
    }

    /** The file of a profile, where the directions go. */
    private val directions: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "comic_directions.preferences_pb") }
    }

    /** The whole store, as a reader takes it. */
    private fun store() = DataStoreComicSettingsStore(DataStoreComicDeviceSettings(dataStore), directions)

    @After
    fun close() = scope.cancel()

    @Test
    fun `a new store shares the defaults`() = runBlocking<Unit> {
        assertThat(store().shared.first()).isEqualTo(ComicSettings())
    }

    @Test
    fun `the shared settings follow the fit, the two pages and the background`() = runBlocking<Unit> {
        val store = store()

        store.shared.test {
            assertThat(awaitItem()).isEqualTo(ComicSettings())

            store.setFit(Fit.Width)
            assertThat(awaitItem()).isEqualTo(ComicSettings(fit = Fit.Width))

            store.setTwoPages(TwoPages.Never)
            assertThat(awaitItem()).isEqualTo(ComicSettings(fit = Fit.Width, twoPages = TwoPages.Never))

            store.setBackground(Background.White)
            assertThat(awaitItem())
                .isEqualTo(ComicSettings(Fit.Width, TwoPages.Never, Direction.LeftToRight, Background.White))
        }
    }

    @Test
    fun `a direction, of a series or of an album, changes nothing of them`() = runBlocking<Unit> {
        val store = store()

        store.shared.test {
            assertThat(awaitItem()).isEqualTo(ComicSettings())

            store.setDirection(DirectionScope.Series(SeriesId("7")), Direction.RightToLeft)
            store.setDirection(DirectionScope.Book(BookId("7")), Direction.RightToLeft)
            store.setBackground(Background.Grey)

            // The next thing said is the background: the directions said nothing.
            assertThat(awaitItem()).isEqualTo(ComicSettings(background = Background.Grey))
        }
    }

    @Test
    fun `what the page chooses is what a reader opens with`() = runBlocking<Unit> {
        val store = store()
        store.setDirection(DirectionScope.Series(SeriesId("7")), Direction.RightToLeft)

        store.setFit(Fit.Width)

        assertThat(store.shared.first()).isEqualTo(ComicSettings(fit = Fit.Width))
        assertThat(store.settings(DirectionScope.Series(SeriesId("7"))).first())
            .isEqualTo(ComicSettings(fit = Fit.Width, direction = Direction.RightToLeft))
    }

    @Test
    fun `a store that cannot be read shares the defaults`() = runBlocking<Unit> {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk")
        }

        assertThat(DataStoreComicDeviceSettings(broken).shared.first()).isEqualTo(ComicSettings())
    }
}
