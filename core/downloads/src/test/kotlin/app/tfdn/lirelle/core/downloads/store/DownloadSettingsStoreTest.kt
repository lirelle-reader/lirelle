package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DownloadSettingsStoreTest {

    @get:Rule
    val dataStores = DataStores()

    @Test
    fun `a new store holds the defaults, metered networks refused`() = runBlocking<Unit> {
        val settings = DataStoreDownloadSettingsStore(dataStores.create("s")).settings.first()

        assertThat(settings).isEqualTo(DownloadSettings())
        assertThat(settings.allowMetered).isFalse()
    }

    @Test
    fun `an update survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create("s")
        DataStoreDownloadSettingsStore(dataStore).update { it.copy(allowMetered = true) }

        assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first())
            .isEqualTo(DownloadSettings(allowMetered = true))
    }

    @Test
    fun `an update starts from what is stored`() = runBlocking<Unit> {
        val store = DataStoreDownloadSettingsStore(dataStores.create("s"))
        store.update { it.copy(allowMetered = true) }

        store.update { it.copy(allowMetered = !it.allowMetered) }

        assertThat(store.settings.first().allowMetered).isFalse()
    }

    @Test
    fun `the notification permission asked once is remembered, beside the network rule`() = runBlocking<Unit> {
        val dataStore = dataStores.create("s")
        val store = DataStoreDownloadSettingsStore(dataStore)
        store.update { it.copy(allowMetered = true) }

        store.update { it.copy(notificationsAsked = true) }

        assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first())
            .isEqualTo(DownloadSettings(allowMetered = true, notificationsAsked = true))
    }

    @Test
    fun `settings stored before the notification question read as not asked`() = runBlocking<Unit> {
        val dataStore = dataStores.create("s")
        dataStore.edit { it[stringPreferencesKey("download_settings")] = """{"allowMetered":true}""" }

        assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first())
            .isEqualTo(DownloadSettings(allowMetered = true, notificationsAsked = false))
    }

    @Test
    fun `the series sheet proposes the text until a choice is made, then the last choice`() = runBlocking<Unit> {
        val dataStore = dataStores.create("s")
        val store = DataStoreDownloadSettingsStore(dataStore)
        assertThat(store.settings.first().seriesFormats).containsExactly(DownloadFormat.Text)

        store.update { it.copy(seriesFormats = setOf(DownloadFormat.Audiobook, DownloadFormat.Narration)) }

        assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first().seriesFormats)
            .containsExactly(DownloadFormat.Audiobook, DownloadFormat.Narration)
    }

    @Test
    fun `a choice of nothing is remembered, and a format this version does not know is left out`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create("s")
            DataStoreDownloadSettingsStore(dataStore).update { it.copy(seriesFormats = emptySet()) }
            assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first().seriesFormats).isEmpty()

            val stored = """{"seriesFormats":["Text","Braille"]}"""
            dataStore.edit { it[stringPreferencesKey("download_settings")] = stored }

            assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first().seriesFormats)
                .containsExactly(DownloadFormat.Text)
        }

    @Test
    fun `an unreadable value reads as the defaults`() = runBlocking<Unit> {
        val dataStore = dataStores.create("s")
        dataStore.edit { it[stringPreferencesKey("download_settings")] = "{not json" }

        assertThat(DataStoreDownloadSettingsStore(dataStore).settings.first()).isEqualTo(DownloadSettings())
    }

    @Test
    fun `a store that cannot be read holds the defaults`() = runBlocking<Unit> {
        val store = DataStoreDownloadSettingsStore(BrokenDataStore)

        store.update { it.copy(allowMetered = true) }

        assertThat(store.settings.first()).isEqualTo(DownloadSettings())
    }
}
