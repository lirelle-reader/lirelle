package app.tfdn.lirelle.core.audio.settings

import androidx.datastore.core.DataStore
import app.tfdn.lirelle.core.ids.BookId
import androidx.datastore.preferences.core.Preferences

/**
 * The player settings, the speed of each book and that of its narration. Two halves, kept apart: the settings
 * are the device's ([PlayerDeviceSettings]), the same for every profile; the speeds of the books are one
 * profile's, and go with it. Whoever plays a book takes the whole, and is given the one of its profile.
 */
interface PlayerSettingsStore : PlayerDeviceSettings {
    /** The speed chosen for this book; null when none was. */
    suspend fun speedOf(bookId: BookId): Float?

    /**
     * Keeps [speed] (within bounds) for this book, and as the speed of the next new book. Two writes, not
     * one: the book's speed in the profile, then the next new book's on the device.
     */
    suspend fun setSpeed(bookId: BookId, speed: Float)

    /** The speed chosen for the narration of this read-along book, apart from [speedOf]; null when none was. */
    suspend fun narrationSpeedOf(bookId: BookId): Float?

    /** Keeps [speed] (within bounds, like [setSpeed]) for the narration of this book; the last speed stays. */
    suspend fun setNarrationSpeed(bookId: BookId, speed: Float)
}

/**
 * The whole store of one profile.
 *
 * @param device the settings, shared with the other profiles.
 * @param bookSpeeds the profile's own file, where the speeds of its books are kept.
 */
class DataStorePlayerSettingsStore(
    private val device: PlayerDeviceSettings,
    bookSpeeds: DataStore<Preferences>,
) : PlayerSettingsStore, PlayerDeviceSettings by device {
    private val speeds = BookSpeeds(bookSpeeds)

    override suspend fun speedOf(bookId: BookId): Float? = speeds.of(bookId)

    /**
     * The book's speed is the profile's; the speed of the next new book, which follows it, is the device's.
     * Two files, so two writes, the profile's first: a profile that closes between them leaves the two out of
     * step, the book at its new speed and the next new book at the one before. Harmlessly: the next new book
     * only starts at the speed chosen before this one.
     */
    override suspend fun setSpeed(bookId: BookId, speed: Float) {
        val valid = speed.toValidSpeed()
        speeds.set(bookId, valid)
        device.update { it.copy(lastSpeed = valid) }
    }

    override suspend fun narrationSpeedOf(bookId: BookId): Float? = speeds.narrationOf(bookId)

    override suspend fun setNarrationSpeed(bookId: BookId, speed: Float) =
        speeds.setNarration(bookId, speed.toValidSpeed())
}
