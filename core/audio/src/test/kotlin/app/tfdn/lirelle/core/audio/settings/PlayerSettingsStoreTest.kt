package app.tfdn.lirelle.core.audio.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.audio.position.BrokenDataStore
import app.tfdn.lirelle.core.audio.position.DataStores
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

private val DeviceKey = stringPreferencesKey("player_settings")
private val ProfileKey = stringPreferencesKey("book_speeds")

class PlayerSettingsStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val device by lazy { dataStores.create("player_settings") }
    private val profile by lazy { dataStores.create("book_speeds") }

    /** The whole store, from the device's file and a profile's. */
    private fun store(
        device: DataStore<Preferences> = this.device,
        profile: DataStore<Preferences> = this.profile,
    ) = DataStorePlayerSettingsStore(DataStorePlayerDeviceSettings(device), profile)

    @Test
    fun `a new store holds the defaults`() = runBlocking<Unit> {
        val settings = store().settings.first()

        assertThat(settings).isEqualTo(PlayerSettings())
        assertThat(settings.backSeconds).isEqualTo(15)
        assertThat(settings.forwardSeconds).isEqualTo(30)
    }

    @Test
    fun `an update survives a new instance`() = runBlocking<Unit> {
        store().update {
            it.copy(
                backSeconds = 10,
                forwardSeconds = 45,
                rewindOnResume = false,
                sleepFade = false,
                shakeToExtend = false,
                lastSleepChoice = SleepChoice.Minutes(20),
                lastSpeed = 1.25f,
            )
        }

        val settings = store().settings.first()

        assertThat(settings).isEqualTo(
            PlayerSettings(10, 45, false, false, false, SleepChoice.Minutes(20), 1.25f),
        )
    }

    @Test
    fun `the end of chapter choice is kept`() = runBlocking<Unit> {
        val store = store()
        store.update { it.copy(lastSleepChoice = SleepChoice.EndOfChapter) }

        assertThat(store.settings.first().lastSleepChoice).isEqualTo(SleepChoice.EndOfChapter)
    }

    @Test
    fun `values are brought within bounds`() = runBlocking<Unit> {
        val store = store()

        store.update { it.copy(backSeconds = 1, forwardSeconds = 600, lastSpeed = 9f) }
        with(store.settings.first()) {
            assertThat(backSeconds).isEqualTo(5)
            assertThat(forwardSeconds).isEqualTo(60)
            assertThat(lastSpeed).isEqualTo(3f)
        }

        store.update { it.copy(backSeconds = 100, forwardSeconds = 2, lastSpeed = 0.1f) }
        with(store.settings.first()) {
            assertThat(backSeconds).isEqualTo(60)
            assertThat(forwardSeconds).isEqualTo(5)
            assertThat(lastSpeed).isEqualTo(0.5f)
        }
    }

    @Test
    fun `speeds snap to steps of 0,05`() = runBlocking<Unit> {
        val store = store()

        store.setSpeed(BookId("1"), 1.1234f)
        store.setSpeed(BookId("2"), 1.18f)

        assertThat(store.speedOf(BookId("1"))).isEqualTo(1.10f)
        assertThat(store.speedOf(BookId("2"))).isEqualTo(1.20f)
    }

    @Test
    fun `each book has its own speed and an unknown book has none`() = runBlocking<Unit> {
        val store = store()
        assertThat(store.speedOf(BookId("7"))).isNull()

        store.setSpeed(BookId("7"), 1.5f)
        store.setSpeed(BookId("8"), 0.75f)
        store.setSpeed(BookId("9"), 9f)
        store.setSpeed(BookId("10"), 0.1f)

        val again = store()
        assertThat(again.speedOf(BookId("7"))).isEqualTo(1.5f)
        assertThat(again.speedOf(BookId("8"))).isEqualTo(0.75f)
        assertThat(again.speedOf(BookId("9"))).isEqualTo(3f)
        assertThat(again.speedOf(BookId("10"))).isEqualTo(0.5f)
        assertThat(again.speedOf(BookId("11"))).isNull()
    }

    @Test
    fun `each book has its own narration speed, apart from its speed`() = runBlocking<Unit> {
        val store = store()
        assertThat(store.narrationSpeedOf(BookId("7"))).isNull()

        store.setSpeed(BookId("7"), 1.5f)
        store.setNarrationSpeed(BookId("7"), 1.25f)
        store.setNarrationSpeed(BookId("8"), 0.75f)

        val again = store()
        assertThat(again.speedOf(BookId("7"))).isEqualTo(1.5f)
        assertThat(again.narrationSpeedOf(BookId("7"))).isEqualTo(1.25f)
        assertThat(again.narrationSpeedOf(BookId("8"))).isEqualTo(0.75f)
        assertThat(again.speedOf(BookId("8"))).isNull()
        assertThat(again.narrationSpeedOf(BookId("9"))).isNull()
    }

    @Test
    fun `narration speeds are brought within bounds and snap like the speeds`() = runBlocking<Unit> {
        val store = store()

        store.setNarrationSpeed(BookId("1"), 1.1234f)
        store.setNarrationSpeed(BookId("2"), 1.18f)
        store.setNarrationSpeed(BookId("3"), 9f)
        store.setNarrationSpeed(BookId("4"), 0.1f)

        assertThat(store.narrationSpeedOf(BookId("1"))).isEqualTo(1.10f)
        assertThat(store.narrationSpeedOf(BookId("2"))).isEqualTo(1.20f)
        assertThat(store.narrationSpeedOf(BookId("3"))).isEqualTo(3f)
        assertThat(store.narrationSpeedOf(BookId("4"))).isEqualTo(0.5f)
    }

    @Test
    fun `a narration speed leaves the last speed and an update keeps the narration speeds`() = runBlocking<Unit> {
        val store = store()
        store.setSpeed(BookId("7"), 1.5f)

        store.setNarrationSpeed(BookId("8"), 2f)
        store.update { it.copy(backSeconds = 20) }

        assertThat(store.settings.first().lastSpeed).isEqualTo(1.5f)
        assertThat(store.narrationSpeedOf(BookId("8"))).isEqualTo(2f)
    }

    @Test
    fun `a narration speed that is not a number is none, the others are kept`() = runBlocking<Unit> {
        profile.edit { it[ProfileKey] = """{"speeds":{"7":1.5},"narrationSpeeds":{"7":1.25,"8":"fast"}}""" }
        val store = store()

        assertThat(store.speedOf(BookId("7"))).isEqualTo(1.5f)
        assertThat(store.narrationSpeedOf(BookId("7"))).isEqualTo(1.25f)
        assertThat(store.narrationSpeedOf(BookId("8"))).isNull()
    }

    @Test
    fun `the last speed follows the last book changed, and an update keeps the book speeds`() = runBlocking<Unit> {
        val store = store()
        store.setSpeed(BookId("7"), 1.5f)

        store.update { it.copy(backSeconds = 20) }

        assertThat(store.settings.first().lastSpeed).isEqualTo(1.5f)
        assertThat(store.speedOf(BookId("7"))).isEqualTo(1.5f)
    }

    @Test
    fun `an unreadable value reads as the defaults`() = runBlocking<Unit> {
        device.edit { it[DeviceKey] = "{not json" }
        profile.edit { it[ProfileKey] = "{not json" }

        assertThat(store().settings.first()).isEqualTo(PlayerSettings())
        assertThat(store().speedOf(BookId("7"))).isNull()
    }

    @Test
    fun `an unknown sleep choice and missing fields read as the defaults`() = runBlocking<Unit> {
        device.edit { it[DeviceKey] = """{"backSeconds":20,"lastSleepChoice":"minutes:abc","extra":1}""" }

        val settings = store().settings.first()

        assertThat(settings).isEqualTo(PlayerSettings(backSeconds = 20))
    }

    @Test
    fun `a store that cannot be read gives the defaults and one that cannot be written is left alone`() =
        runBlocking<Unit> {
            val store = store(BrokenDataStore, BrokenDataStore)

            store.update { it.copy(backSeconds = 20) }
            store.setSpeed(BookId("1"), 2f)
            store.setNarrationSpeed(BookId("1"), 2f)

            assertThat(store.speedOf(BookId("1"))).isNull()
            assertThat(store.narrationSpeedOf(BookId("1"))).isNull()
            assertThat(store.settings.first()).isEqualTo(PlayerSettings())
        }

    @Test
    fun `one wrongly typed field does not reset the others, nor one wrong book speed the others`() =
        runBlocking<Unit> {
            device.edit {
                it[DeviceKey] = """{"backSeconds":"abc","forwardSeconds":40,"sleepFade":3,"lastSleepChoice":7,""" +
                    """"lastSpeed":1.25}"""
            }
            profile.edit { it[ProfileKey] = """{"speeds":{"7":1.5,"8":"fast","9":2.0},"narrationSpeeds":3}""" }
            val store = store()

            assertThat(store.settings.first()).isEqualTo(PlayerSettings(forwardSeconds = 40, lastSpeed = 1.25f))
            assertThat(store.speedOf(BookId("7"))).isEqualTo(1.5f)
            assertThat(store.speedOf(BookId("8"))).isNull()
            assertThat(store.speedOf(BookId("9"))).isEqualTo(2f)
            assertThat(store.narrationSpeedOf(BookId("7"))).isNull()
        }

    @Test
    fun `the speed of a book is written to the profile's file, not to the device's`() = runBlocking<Unit> {
        val store = store()

        store.setSpeed(BookId("7"), 1.5f)
        store.setNarrationSpeed(BookId("7"), 1.25f)

        assertThat(profile.data.first()[ProfileKey]).isEqualTo("""{"speeds":{"7":1.5},"narrationSpeeds":{"7":1.25}}""")
        val onDevice = checkNotNull(device.data.first()[DeviceKey])
        assertThat(onDevice).doesNotContain("speeds")
        assertThat(onDevice).doesNotContain("\"7\"")
        assertThat(profile.data.first()[DeviceKey]).isNull()
    }

    @Test
    fun `the speed of a book is still the device's speed of the next new book`() = runBlocking<Unit> {
        store().setSpeed(BookId("7"), 1.5f)

        assertThat(DataStorePlayerDeviceSettings(device).settings.first().lastSpeed).isEqualTo(1.5f)
        assertThat(device.data.first()[DeviceKey]).contains("\"lastSpeed\":1.5")
    }

    @Test
    fun `the settings are written to the device's file, and nothing of them to the profile's`() = runBlocking<Unit> {
        store().update { it.copy(backSeconds = 25) }

        assertThat(device.data.first()[DeviceKey]).contains("\"backSeconds\":25")
        assertThat(profile.data.first().asMap()).isEmpty()
    }

    @Test
    fun `two profiles on one device share the settings, and not the speeds of their books`() = runBlocking<Unit> {
        val one = store()
        val other = store(profile = dataStores.create("book_speeds_of_another"))

        one.update { it.copy(backSeconds = 25) }
        one.setSpeed(BookId("7"), 1.5f)
        one.setNarrationSpeed(BookId("7"), 1.25f)

        assertThat(other.settings.first().backSeconds).isEqualTo(25)
        assertThat(other.settings.first().lastSpeed).isEqualTo(1.5f)
        assertThat(other.speedOf(BookId("7"))).isNull()
        assertThat(other.narrationSpeedOf(BookId("7"))).isNull()

        other.setSpeed(BookId("7"), 2f)

        assertThat(one.speedOf(BookId("7"))).isEqualTo(1.5f)
        assertThat(other.speedOf(BookId("7"))).isEqualTo(2f)
        assertThat(one.settings.first().lastSpeed).isEqualTo(2f)
    }

    /** What an installation from before the split left there is only ignored: the migration moves it first. */
    @Test
    fun `book speeds left in the device's file are not read as the profile's`() = runBlocking<Unit> {
        device.edit { it[DeviceKey] = """{"backSeconds":20,"speeds":{"7":1.5},"narrationSpeeds":{"7":1.25}}""" }
        val store = store()

        assertThat(store.settings.first()).isEqualTo(PlayerSettings(backSeconds = 20))
        assertThat(store.speedOf(BookId("7"))).isNull()
        assertThat(store.narrationSpeedOf(BookId("7"))).isNull()
    }
}
