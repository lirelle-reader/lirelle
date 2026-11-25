package app.tfdn.lirelle.feature.comics.strip

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val Width = 40
private const val Height = 6000

/** The slices of a tall page, decoded from its file: a picture red in its upper half, blue in its lower one. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliceDecodersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tallPage(name: String = "page.png"): File {
        val pixels = IntArray(Width * Height) { if (it / Width < Height / 2) Color.RED else Color.BLUE }
        val picture = Bitmap.createBitmap(pixels, Width, Height, Bitmap.Config.ARGB_8888)
        return folder.newFile(name).also { file ->
            file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `a slice is the rows asked, in the picture's own pixels`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))

        val slice = checkNotNull(decoders.decode(tallPage(), StripSlice(2900, 3100), sample = 1))

        assertThat(slice.width).isEqualTo(Width)
        assertThat(slice.height).isEqualTo(200)
        assertThat(slice.getPixel(0, 0)).isEqualTo(Color.RED)
        assertThat(slice.getPixel(0, 199)).isEqualTo(Color.BLUE)
        decoders.close()
    }

    @Test
    fun `a slice sampled keeps one pixel in two each way`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))

        val slice = checkNotNull(decoders.decode(tallPage(), StripSlice(2900, 3100), sample = 2))

        assertThat(slice.width).isEqualTo(Width / 2)
        assertThat(slice.height).isEqualTo(100)
        decoders.close()
    }

    @Test
    fun `a file that is no picture, or that is gone, has no slice`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))
        val text = folder.newFile("page.txt").apply { writeText("not a picture") }
        val gone = File(folder.root, "gone.png")

        assertThat(decoders.decode(text, StripSlice(0, 100), sample = 1)).isNull()
        assertThat(decoders.decode(gone, StripSlice(0, 100), sample = 1)).isNull()
    }

    @Test
    fun `a picture stays open from a slice to the next, and nothing is opened once closed`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))
        val page = tallPage()

        assertThat(decoders.decode(page, StripSlice(0, 100), sample = 1)?.getPixel(0, 0)).isEqualTo(Color.RED)
        assertThat(decoders.openCount).isEqualTo(1)
        assertThat(decoders.decode(page, StripSlice(5900, 6000), sample = 1)?.getPixel(0, 0)).isEqualTo(Color.BLUE)
        assertThat(decoders.openCount).isEqualTo(1)

        decoders.close()
        assertThat(decoders.openCount).isEqualTo(0)

        // Closed for good: a slice asked by a strip that is gone opens no picture, which nothing would close.
        assertThat(decoders.decode(page, StripSlice(5900, 6000), sample = 1)).isNull()
        assertThat(decoders.decode(tallPage("other.png"), StripSlice(0, 100), sample = 1)).isNull()
        assertThat(decoders.openCount).isEqualTo(0)
    }

    @Test
    fun `only a few pictures stay open, the one asked longest ago closed first`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))
        val pages = List(6) { tallPage("page-$it.png") }

        pages.forEach { assertThat(decoders.decode(it, StripSlice(0, 100), sample = 1)).isNotNull() }
        assertThat(decoders.openCount).isEqualTo(4)

        // The first one was closed: it is opened again, and read.
        assertThat(decoders.decode(pages.first(), StripSlice(0, 100), sample = 1)?.getPixel(0, 0)).isEqualTo(Color.RED)
        assertThat(decoders.openCount).isEqualTo(4)
        decoders.close()
    }

    @Test
    fun `rows asked past the picture's end are cut at its end`() = runTest {
        val decoders = SliceDecoders(UnconfinedTestDispatcher(testScheduler))

        val slice = checkNotNull(decoders.decode(tallPage(), StripSlice(5900, 6100), sample = 1))

        assertThat(slice.height).isEqualTo(100)
        assertThat(decoders.decode(tallPage("other.png"), StripSlice(6000, 6100), sample = 1)).isNull()
        decoders.close()
    }
}
