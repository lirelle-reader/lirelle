package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** The page of a position, written on the device and read back; and what was written before pages were. */
class PagePositionStoredTest {

    @get:Rule
    val dataStores = DataStores()

    private val at = Instant.parse("2026-11-14T10:00:00Z")
    private val album = FileId("31")
    private val book = FileId("12")
    private val turned = LocalPosition(album, cfi = null, percent = 25.0, at = at, page = 75)

    /** What the values written before the profiles also carried. */
    private val owner = """{"serverUrl":"https://books.example","userId":7}"""
    private val before = """{"fileId":"12","cfi":"epubcfi(/6/4)","percent":3.0,"at":"2026-11-14T10:00:00Z"}"""

    @Test
    fun `the page of a kept position survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStorePositionStore(dataStore).put(turned)

        assertThat(DataStorePositionStore(dataStore).get(album)).isEqualTo(turned)
    }

    @Test
    fun `the page of a queued position survives a new instance, and leaves once sent`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStoreOutbox(dataStore).enqueuePosition(turned)
        val outbox = DataStoreOutbox(dataStore)

        assertThat(outbox.pending().positions).containsExactly(turned)

        outbox.markSent(turned)
        assertThat(outbox.pending().positions).isEmpty()
    }

    @Test
    fun `a position kept before pages were reads without one`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("positions")] = """{"owner":$owner,"positions":[$before]}""" }

        assertThat(DataStorePositionStore(dataStore).get(book))
            .isEqualTo(LocalPosition(book,"epubcfi(/6/4)", 3.0, at, page = null))
    }

    @Test
    fun `a position queued before pages were reads without one`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("outbox")] = """{"owner":$owner,"positions":[$before]}""" }

        assertThat(DataStoreOutbox(dataStore).pending().positions)
            .containsExactly(LocalPosition(book,"epubcfi(/6/4)", 3.0, at, page = null))
    }

    @Test
    fun `a position without a page is written as it was before pages were`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        DataStorePositionStore(dataStore).put(LocalPosition(book,"epubcfi(/6/4)", 3.0, at))

        val written = dataStore.data.first()[stringPreferencesKey("positions")]
        assertThat(written).isEqualTo("""{"positions":[$before]}""")
    }
}
