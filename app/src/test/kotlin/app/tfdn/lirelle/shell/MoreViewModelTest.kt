package app.tfdn.lirelle.shell

import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** More offers the reading history when the profile's source keeps one (spec 2c). */
class MoreViewModelTest {

    @Test
    fun `a Komga-like source keeps a history, and More offers it`() {
        assertThat(MoreViewModel(KomgaLikeCapabilities).history).isTrue()
    }

    @Test
    fun `a source without history has no History entry`() {
        assertThat(MoreViewModel(KomgaLikeCapabilities.copy(history = false)).history).isFalse()
    }

    @Test
    fun `BookOrbit keeps its History entry`() {
        assertThat(MoreViewModel(SourceCapabilities.Everything).history).isTrue()
    }
}
