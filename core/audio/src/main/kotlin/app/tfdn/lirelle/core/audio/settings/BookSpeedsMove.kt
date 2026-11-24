package app.tfdn.lirelle.core.audio.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The fields of the device's player settings that were the speeds of the books, and are the profile's now. */
private val SpeedFields = setOf("speeds", "narrationSpeeds")

/**
 * Moves the speed of each book out of the player settings of an installation from before the profiles, where
 * they were fields of the device's one value, into the speeds of a profile. For the migration, and before the
 * device's settings are written by anything else: their first write drops the fields it does not know.
 *
 * Copied first and removed after, as they were written: interrupted in between, it does the same again. Once
 * the fields are gone from the device's value nothing is touched, and a value that is not an object is left
 * as it is, for the store to read as it always did.
 *
 * @param device the device's player settings.
 * @param profile where the speeds go; null when they are nobody's any more, and are only removed.
 */
suspend fun moveBookSpeeds(device: DataStore<Preferences>, profile: DataStore<Preferences>?) {
    val deviceKey = stringPreferencesKey(PlayerSettingsName)
    val settings = device.data.first()[deviceKey]?.let(::objectOrNull) ?: return
    val speeds = settings.filterKeys { it in SpeedFields }
    if (speeds.isEmpty()) return
    profile?.edit { it[stringPreferencesKey(BookSpeedsName)] = JsonObject(speeds).toString() }
    device.edit { it[deviceKey] = JsonObject(settings - SpeedFields).toString() }
}

private fun objectOrNull(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
}
