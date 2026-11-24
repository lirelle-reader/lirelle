package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The comics settings that are the device's: the fit, the two pages, the background, and what reading panel by
 * panel takes from the device. The same for every profile, and kept when a profile goes: nothing of an account
 * is in them, and the interface offers no way to clear them.
 */
interface ComicDeviceSettings {
    /**
     * The settings that belong to no series: the fit, the two pages, the background and whether a page read
     * panel by panel is shown whole first, each time one of them changes. The direction in it, and whether the
     * series is read panel by panel, are the default ones and mean nothing.
     */
    val shared: Flow<ComicSettings>

    /** Whether the device was already told what reading panel by panel is and where it falls short. */
    val panelNoticeSeen: Flow<Boolean>

    suspend fun setFit(fit: Fit)

    suspend fun setTwoPages(twoPages: TwoPages)

    suspend fun setBackground(background: Background)

    /** Whether, panel by panel, each page is shown whole before its first panel. */
    suspend fun setWholePageFirst(first: Boolean)

    /** The device was told what reading panel by panel is: it is not told again. */
    suspend fun seePanelNotice()
}

private val FitKey = stringPreferencesKey("fit")
private val TwoPagesKey = stringPreferencesKey("twoPages")
private val BackgroundKey = stringPreferencesKey("background")
private val WholePageFirstKey = booleanPreferencesKey("panels.whole_first")
private val PanelNoticeSeenKey = booleanPreferencesKey("panels.notice_seen")

/** The choice of that name, or [default] for a name the app does not know and for none. */
private inline fun <reified E : Enum<E>> String?.or(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

/**
 * The device's half of the comics settings, in a file of the device: one for all the profiles.
 *
 * Each choice is kept by its name under a key of its own: a name the app does not know, or none, reads as the
 * default; what is on or off is kept as such. A store that cannot be read holds the defaults; one that cannot
 * be written is left as it was. What the file holds besides — the directions an installation from before the
 * profiles kept here — is not read.
 */
class DataStoreComicDeviceSettings(private val dataStore: DataStore<Preferences>) : ComicDeviceSettings {

    override val shared: Flow<ComicSettings> = stored()
        .map {
            val default = ComicSettings()
            default.copy(
                fit = it[FitKey].or(default.fit),
                twoPages = it[TwoPagesKey].or(default.twoPages),
                background = it[BackgroundKey].or(default.background),
                wholePageFirst = it[WholePageFirstKey] ?: default.wholePageFirst,
            )
        }
        .distinctUntilChanged()

    override val panelNoticeSeen: Flow<Boolean> =
        stored().map { it[PanelNoticeSeenKey] == true }.distinctUntilChanged()

    override suspend fun setFit(fit: Fit) = change { it[FitKey] = fit.name }

    override suspend fun setTwoPages(twoPages: TwoPages) = change { it[TwoPagesKey] = twoPages.name }

    override suspend fun setBackground(background: Background) = change { it[BackgroundKey] = background.name }

    override suspend fun setWholePageFirst(first: Boolean) = change { it[WholePageFirstKey] = first }

    override suspend fun seePanelNotice() = change { it[PanelNoticeSeenKey] = true }

    /** What the file holds, each time it changes; nothing when it cannot be read. */
    private fun stored(): Flow<Preferences> =
        dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private suspend fun change(transform: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (_: IOException) {
            // Not written: the settings stay as they were.
        }
    }
}
