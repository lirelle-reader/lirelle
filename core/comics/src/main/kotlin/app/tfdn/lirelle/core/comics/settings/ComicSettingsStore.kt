package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The settings of the comics reader. Two halves, kept apart: how comics are shown is the device's
 * ([ComicDeviceSettings]), the same for every profile; the reading directions, the series read panel by panel
 * and the albums already told of are one profile's, and go with it, as they say which series were read. Whoever
 * reads an album takes the whole, and is given the one of its profile.
 */
interface ComicSettingsStore : ComicDeviceSettings {
    /**
     * The settings of an album whose reading direction is remembered for [scope], each time they change. Its
     * direction is the one chosen for [scope], else [default] (its server's, say), else the reader's own; it is
     * read panel by panel when that was turned on for [scope].
     */
    fun settings(scope: DirectionScope, default: Direction? = null): Flow<ComicSettings>

    /** Remembers [direction] for [scope] alone. */
    suspend fun setDirection(scope: DirectionScope, direction: Direction)

    /** Turns reading panel by panel on or off for [scope]. */
    suspend fun setPanels(scope: DirectionScope, on: Boolean)

    /** Whether the user was already told that the album [bookId] lends itself badly to it. */
    suspend fun albumTold(bookId: BookId): Boolean

    /** The user was told that the album [bookId] lends itself badly to it: it is not said again. */
    suspend fun tellAlbum(bookId: BookId)
}

/**
 * The whole store of one profile.
 *
 * @param device how comics are shown, shared with the other profiles.
 * @param directions the profile's own file, where the directions of its series and albums are kept, and with
 *   them which are read panel by panel.
 */
class DataStoreComicSettingsStore(
    private val device: ComicDeviceSettings,
    directions: DataStore<Preferences>,
) : ComicSettingsStore, ComicDeviceSettings by device {
    private val panels = PanelChoices(directions)
    private val directions = ReadingDirections(directions)

    override fun settings(scope: DirectionScope, default: Direction?): Flow<ComicSettings> =
        combine(device.shared, directions.of(scope), panels.of(scope)) { shared, direction, panels ->
            shared.copy(direction = direction ?: default ?: shared.direction, panels = panels)
        }.distinctUntilChanged()

    override suspend fun setDirection(scope: DirectionScope, direction: Direction) = directions.set(scope, direction)

    override suspend fun setPanels(scope: DirectionScope, on: Boolean) = panels.set(scope, on)

    override suspend fun albumTold(bookId: BookId): Boolean = panels.told(bookId)

    override suspend fun tellAlbum(bookId: BookId) = panels.tell(bookId)
}
