package app.tfdn.lirelle.feature.reader

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The fonts the page asks for under `/fonts/` are in the app, as WOFF2. */
@RunWith(RobolectricTestRunner::class)
class ReaderFontsTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test
    fun `the three families ship in regular, italic and bold, as WOFF2`() {
        val shipped = assets.list("fonts").orEmpty().toList()

        for (family in listOf("LirelleSerif", "LirelleSans", "LirelleDyslexic")) {
            for (face in listOf("Regular", "Italic", "Bold")) {
                val name = "$family-$face.woff2"
                assertWithMessage("fonts").that(shipped).contains(name)
                val bytes = assets.open("fonts/$name").use { it.readBytes() }
                assertWithMessage("size of $name").that(bytes.size).isGreaterThan(Signature.length)
                assertWithMessage("signature of $name")
                    .that(bytes.copyOf(Signature.length).toString(Charsets.US_ASCII))
                    .isEqualTo(Signature)
            }
        }
    }

    @Test
    fun `the fonts come with their licences`() {
        val licences = assets.open("fonts/LICENSES.md").use { it.readBytes().toString(Charsets.UTF_8) }

        for (family in listOf("Literata", "Atkinson Hyperlegible", "OpenDyslexic")) {
            assertWithMessage("licences").that(licences).contains(family)
        }
        assertWithMessage("licences").that(licences).contains("SIL OPEN FONT LICENSE Version 1.1")
    }

    private companion object {
        const val Signature = "wOF2"
    }
}
