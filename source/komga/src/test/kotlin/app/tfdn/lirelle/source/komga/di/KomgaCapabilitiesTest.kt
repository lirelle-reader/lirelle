package app.tfdn.lirelle.source.komga.di

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What Komga can do is its column of the capabilities' table (spec 2c), which the screens' tests stand on. */
class KomgaCapabilitiesTest {

    @Test
    fun `Komga declares its column of the table, as the screens' tests know it`() {
        assertThat(KomgaLibraryModule.capabilities()).isEqualTo(KomgaLikeCapabilities)
    }

    @Test
    fun `a Komga library opens on its series`() {
        assertThat(KomgaLibraryModule.capabilities().libraryStart).isEqualTo(LibraryStart.Series)
    }
}
