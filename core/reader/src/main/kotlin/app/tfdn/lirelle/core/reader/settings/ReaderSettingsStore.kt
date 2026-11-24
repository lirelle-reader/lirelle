package app.tfdn.lirelle.core.reader.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.reader.store.JsonValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The reading settings, kept on the device. They are the device's, not a profile's: they live outside
 * every profile's folder, stay when one is removed, and the interface offers no way to clear them.
 */
interface ReaderSettingsStore {
    /** Always within bounds. */
    val settings: Flow<ReaderSettings>

    /** Applies [transform] to what is stored; the result is stored within bounds. */
    suspend fun update(transform: (ReaderSettings) -> ReaderSettings)
}

/** Every field is optional, and a choice is kept by its name: what cannot be read is the default. */
@Serializable
internal data class StoredSettings(
    val theme: String? = null,
    val font: String? = null,
    val fontSizeStep: Int? = null,
    val lineHeightStep: Int? = null,
    val margins: String? = null,
    val align: String? = null,
    val hyphenate: Boolean? = null,
    val flow: String? = null,
    val volumeKeys: Boolean? = null,
    val brightnessEdge: String? = null,
    val brightness: Float? = null,
    val headerLeft: String? = null,
    val headerRight: String? = null,
    val footerLeft: String? = null,
    val footerRight: String? = null,
    val footnotePanel: Boolean? = null,
    val cornerBookmark: Boolean? = null,
    val lastHighlightColour: String? = null,
)

private inline fun <reified E : Enum<E>> String?.or(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

internal fun StoredSettings.settings(): ReaderSettings {
    val default = ReaderSettings()
    return ReaderSettings(
        theme = theme.or(default.theme),
        font = font.or(default.font),
        fontSizeStep = fontSizeStep ?: default.fontSizeStep,
        lineHeightStep = lineHeightStep ?: default.lineHeightStep,
        margins = margins.or(default.margins),
        align = align.or(default.align),
        hyphenate = hyphenate ?: default.hyphenate,
        flow = flow.or(default.flow),
        volumeKeys = volumeKeys ?: default.volumeKeys,
        brightnessEdge = brightnessEdge.or(default.brightnessEdge),
        brightness = brightness,
        header = RunningLine(headerLeft.or(default.header.left), headerRight.or(default.header.right)),
        footer = RunningLine(footerLeft.or(default.footer.left), footerRight.or(default.footer.right)),
        footnotePanel = footnotePanel ?: default.footnotePanel,
        cornerBookmark = cornerBookmark ?: default.cornerBookmark,
        lastHighlightColour = lastHighlightColour ?: default.lastHighlightColour,
    ).normalized()
}

internal fun ReaderSettings.stored() = StoredSettings(
    theme = theme.name,
    font = font.name,
    fontSizeStep = fontSizeStep,
    lineHeightStep = lineHeightStep,
    margins = margins.name,
    align = align.name,
    hyphenate = hyphenate,
    flow = flow.name,
    volumeKeys = volumeKeys,
    brightnessEdge = brightnessEdge.name,
    brightness = brightness,
    headerLeft = header.left.name,
    headerRight = header.right.name,
    footerLeft = footer.left.name,
    footerRight = footer.right.name,
    footnotePanel = footnotePanel,
    cornerBookmark = cornerBookmark,
    lastHighlightColour = lastHighlightColour,
)

class DataStoreReaderSettingsStore(dataStore: DataStore<Preferences>) : ReaderSettingsStore {
    private val value = JsonValue(dataStore, "settings", StoredSettings.serializer(), StoredSettings())

    override val settings: Flow<ReaderSettings> = value.flow.map { it.settings() }

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) =
        value.change { transform(it.settings()).normalized().stored() }
}
