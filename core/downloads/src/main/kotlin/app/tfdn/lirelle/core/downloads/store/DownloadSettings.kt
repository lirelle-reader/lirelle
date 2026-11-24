package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * @property allowMetered whether downloads may use a metered network.
 * @property notificationsAsked whether the permission to show notifications was asked for: it is asked once.
 * @property seriesFormats the formats last chosen when a whole series was downloaded: what the sheet of a series
 *   proposes the next time.
 */
data class DownloadSettings(
    val allowMetered: Boolean = false,
    val notificationsAsked: Boolean = false,
    val seriesFormats: Set<DownloadFormat> = setOf(DownloadFormat.Text),
)

/**
 * The download settings, kept on the device. They are the device's, not the account's: nothing here
 * is cleared at sign-out, and the interface offers no way to.
 */
interface DownloadSettingsStore {
    val settings: Flow<DownloadSettings>

    /** Applies [change] to what is stored. */
    suspend fun update(change: (DownloadSettings) -> DownloadSettings)
}

/** Every field is optional: what is not there is the default. */
@Serializable
internal data class StoredDownloadSettings(
    val allowMetered: Boolean? = null,
    val notificationsAsked: Boolean? = null,
    val seriesFormats: List<String>? = null,
)

private fun StoredDownloadSettings.settings() = DownloadSettings(
    allowMetered = allowMetered ?: DownloadSettings().allowMetered,
    notificationsAsked = notificationsAsked ?: DownloadSettings().notificationsAsked,
    // A name this version does not know is left out.
    seriesFormats = seriesFormats?.let { names -> DownloadFormat.entries.filterTo(mutableSetOf()) { it.name in names } }
        ?: DownloadSettings().seriesFormats,
)

class DataStoreDownloadSettingsStore(dataStore: DataStore<Preferences>) : DownloadSettingsStore {
    private val value =
        JsonValue(dataStore, "download_settings", StoredDownloadSettings.serializer(), StoredDownloadSettings())

    override val settings: Flow<DownloadSettings> = value.flow.map { it.settings() }

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) =
        value.change {
            val changed = change(it.settings())
            it.copy(
                allowMetered = changed.allowMetered,
                notificationsAsked = changed.notificationsAsked,
                seriesFormats = changed.seriesFormats.map { format -> format.name },
            )
        }
}
