package app.tfdn.lirelle.feature.settings.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
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

class AppearanceStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "appearance.preferences_pb") }
    }

    @After
    fun close() = scope.cancel()

    @Test
    fun `a new store follows the system`() = runBlocking<Unit> {
        assertThat(DataStoreAppearanceStore(dataStore).theme.first()).isEqualTo(AppTheme.System)
    }

    @Test
    fun `a theme written is read back, each time it changes`() = runBlocking<Unit> {
        val store = DataStoreAppearanceStore(dataStore)

        store.theme.test {
            assertThat(awaitItem()).isEqualTo(AppTheme.System)

            store.setTheme(AppTheme.Dark)
            assertThat(awaitItem()).isEqualTo(AppTheme.Dark)

            store.setTheme(AppTheme.Light)
            assertThat(awaitItem()).isEqualTo(AppTheme.Light)

            store.setTheme(AppTheme.System)
            assertThat(awaitItem()).isEqualTo(AppTheme.System)
        }
    }

    @Test
    fun `the theme survives a new instance`() = runBlocking<Unit> {
        DataStoreAppearanceStore(dataStore).setTheme(AppTheme.Light)

        assertThat(DataStoreAppearanceStore(dataStore).theme.first()).isEqualTo(AppTheme.Light)
    }

    @Test
    fun `the theme is kept by its name under the key theme`() = runBlocking<Unit> {
        DataStoreAppearanceStore(dataStore).setTheme(AppTheme.Dark)

        assertThat(dataStore.data.first()[stringPreferencesKey("theme")]).isEqualTo("Dark")
    }

    @Test
    fun `a name the app does not know reads as the system`() = runBlocking<Unit> {
        val store = DataStoreAppearanceStore(dataStore)
        store.setTheme(AppTheme.Dark)

        dataStore.edit { it[stringPreferencesKey("theme")] = "Sepia" }

        assertThat(store.theme.first()).isEqualTo(AppTheme.System)
    }

    @Test
    fun `a store that cannot be read or written follows the system`() = runBlocking<Unit> {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk")
        }
        val store = DataStoreAppearanceStore(broken)

        val written = store.setTheme(AppTheme.Dark)

        assertThat(written).isFalse()
        assertThat(store.theme.first()).isEqualTo(AppTheme.System)
    }

    @Test
    fun `a write tells that the theme was written`() = runBlocking<Unit> {
        assertThat(DataStoreAppearanceStore(dataStore).setTheme(AppTheme.Dark)).isTrue()
    }
}
