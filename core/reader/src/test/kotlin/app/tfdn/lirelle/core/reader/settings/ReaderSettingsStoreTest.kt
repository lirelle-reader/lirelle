package app.tfdn.lirelle.core.reader.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.reader.sync.BrokenDataStore
import app.tfdn.lirelle.core.reader.sync.DataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ReaderSettingsStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val key = stringPreferencesKey("settings")

    private val changed = ReaderSettings(
        theme = ReaderTheme.Sepia,
        font = ReaderFont.Dyslexic,
        fontSizeStep = 2,
        lineHeightStep = 1,
        margins = MarginSize.Wide,
        align = ReaderAlign.Justify,
        hyphenate = false,
        flow = ReaderFlow.Scrolled,
        volumeKeys = true,
        brightnessEdge = BrightnessEdge.Off,
        brightness = 0.35f,
        header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
        footer = RunningLine(RunningSlot.Battery, RunningSlot.Time),
        footnotePanel = false,
        cornerBookmark = false,
        lastHighlightColour = "#F472B6",
    )

    @Test
    fun `a new store holds the defaults`() = runBlocking<Unit> {
        assertThat(DataStoreReaderSettingsStore(dataStores.create()).settings.first()).isEqualTo(ReaderSettings())
    }

    @Test
    fun `an update is applied to what is stored`() = runBlocking<Unit> {
        val store = DataStoreReaderSettingsStore(dataStores.create())

        store.update { it.copy(theme = ReaderTheme.Black) }
        store.update { it.copy(fontSizeStep = it.fontSizeStep + 1) }

        assertThat(store.settings.first())
            .isEqualTo(ReaderSettings(theme = ReaderTheme.Black, fontSizeStep = DefaultFontStep + 1))
    }

    @Test
    fun `every setting survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStoreReaderSettingsStore(dataStore).update { changed }

        assertThat(DataStoreReaderSettingsStore(dataStore).settings.first()).isEqualTo(changed)
    }

    @Test
    fun `the percentage of the chapter survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val lines = ReaderSettings(
            header = RunningLine(RunningSlot.ChapterPercent, RunningSlot.BookPercent),
            footer = RunningLine(RunningSlot.Nothing, RunningSlot.ChapterPercent),
        )
        DataStoreReaderSettingsStore(dataStore).update { lines }

        assertThat(DataStoreReaderSettingsStore(dataStore).settings.first()).isEqualTo(lines)
    }

    @Test
    fun `an update out of bounds is stored clamped`() = runBlocking<Unit> {
        val store = DataStoreReaderSettingsStore(dataStores.create())

        store.update { it.copy(fontSizeStep = 99, lineHeightStep = -1, brightness = 4f) }

        val stored = store.settings.first()
        assertThat(stored.fontSizeStep).isEqualTo(FontSizesPx.lastIndex)
        assertThat(stored.lineHeightStep).isEqualTo(0)
        assertThat(stored.brightness).isEqualTo(1f)
    }

    @Test
    fun `stored values out of bounds are read clamped`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[key] = """{"fontSizeStep":40,"lineHeightStep":-2,"brightness":0.0}""" }

        val stored = DataStoreReaderSettingsStore(dataStore).settings.first()

        assertThat(stored.fontSizeStep).isEqualTo(FontSizesPx.lastIndex)
        assertThat(stored.lineHeightStep).isEqualTo(0)
        assertThat(stored.brightness).isEqualTo(0.01f)
    }

    @Test
    fun `an unknown name falls back to the default of that field only`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit {
            it[key] = """{"theme":"Neon","font":"Serif","align":"Centre","flow":"Scrolled","margins":"Huge",""" +
                """"brightnessEdge":"Right","headerLeft":"Weather","headerRight":"Battery","later":true}"""
        }

        val stored = DataStoreReaderSettingsStore(dataStore).settings.first()

        assertThat(stored).isEqualTo(
            ReaderSettings(
                font = ReaderFont.Serif,
                flow = ReaderFlow.Scrolled,
                brightnessEdge = BrightnessEdge.Right,
                header = RunningLine(RunningSlot.ChapterTitle, RunningSlot.Battery),
            ),
        )
    }

    @Test
    fun `a corrupt store reads as the defaults and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[key] = "not json" }
        val store = DataStoreReaderSettingsStore(dataStore)

        assertThat(store.settings.first()).isEqualTo(ReaderSettings())

        store.update { it.copy(hyphenate = false) }
        assertThat(store.settings.first()).isEqualTo(ReaderSettings(hyphenate = false))
    }

    @Test
    fun `a store that cannot be read or written holds the defaults and never fails`() = runBlocking<Unit> {
        val store = DataStoreReaderSettingsStore(BrokenDataStore)

        store.update { it.copy(hyphenate = false) }

        assertThat(store.settings.first()).isEqualTo(ReaderSettings())
    }

    @Test
    fun `an unknown stored highlight colour falls back to yellow`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[key] = """{"lastHighlightColour":"banana","footnotePanel":false}""" }

        val stored = DataStoreReaderSettingsStore(dataStore).settings.first()

        assertThat(stored.lastHighlightColour).isEqualTo("#FACC15")
        assertThat(stored.footnotePanel).isFalse()
        assertThat(stored.cornerBookmark).isTrue()
    }

    @Test
    fun `a colour that is not well formed is not stored`() = runBlocking<Unit> {
        val store = DataStoreReaderSettingsStore(dataStores.create())

        store.update { it.copy(lastHighlightColour = "url(x)") }

        assertThat(store.settings.first().lastHighlightColour).isEqualTo("#FACC15")
    }
}
