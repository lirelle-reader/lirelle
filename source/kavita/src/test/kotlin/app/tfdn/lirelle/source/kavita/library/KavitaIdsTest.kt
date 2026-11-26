package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Kavita numbers its series, libraries and reading lists: the app's ids are those numbers written out. */
class KavitaIdsTest {

    @Test
    fun `Kavita's numbers are the app's ids`() {
        assertThat(261.asSeriesId()).isEqualTo(SeriesId("261"))
        assertThat(7.asShelfId()).isEqualTo(ShelfId("7"))
        assertThat(3.asLibraryId()).isEqualTo(LibraryId("3"))
    }

    @Test
    fun `an id is read back as Kavita's number`() {
        assertThat(SeriesId("261").kavitaNumber()).isEqualTo(261)
        assertThat(ShelfId("7").kavitaNumber()).isEqualTo(7)
    }

    @Test
    fun `an id that is no Kavita number is none`() {
        listOf("", "abc", "v407", "0", "-3", "+3", " 3", "99999999999").forEach {
            assertThat(SeriesId(it).kavitaNumber()).isNull()
            assertThat(ShelfId(it).kavitaNumber()).isNull()
        }
    }
}
