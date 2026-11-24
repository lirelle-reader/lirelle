package app.tfdn.lirelle.core.comics.panels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val TopLeft = 0xFF0000
private const val TopRight = 0x00FF00
private const val BottomLeft = 0x0000FF
private const val BottomRight = 0xFFFF00
private const val Rgb = 0xFFFFFF
private const val Opaque = 0xFF shl 24
private const val HalfThrough = 128

/** A page's file read for the cut. With the platform's own decoder: the stand-in decodes and scales nothing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagePictureTest {

    private fun file(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = Files.createTempFile("page", "").toFile()
        file.outputStream().use { bitmap.compress(format, 100, it) }
        bitmap.recycle()
        return file
    }

    /** A picture of four quarters, each of its own colour: channels swapped or rows flipped would show. */
    private fun quarters(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val midX = width / 2f
        val midY = height / 2f
        fun fill(left: Float, top: Float, right: Float, bottom: Float, rgb: Int) =
            canvas.drawRect(left, top, right, bottom, Paint().apply { color = rgb or Opaque })
        fill(0f, 0f, midX, midY, TopLeft)
        fill(midX, 0f, width.toFloat(), midY, TopRight)
        fill(0f, midY, midX, height.toFloat(), BottomLeft)
        fill(midX, midY, width.toFloat(), height.toFloat(), BottomRight)
        return bitmap
    }

    private fun decoded(file: File): PagePixels = (decodeForCut(file) as PageDecoding.Picture).pixels

    private fun PagePixels.rgb(x: Int, y: Int): Int = argb[y * width + x] and Rgb

    /** The picture of four quarters of that size comes out at the size [expected], each quarter in its corner. */
    private fun assertDecoded(width: Int, height: Int, expected: Pair<Int, Int>) {
        val pixels = decoded(file(quarters(width, height)))

        assertThat(pixels.width to pixels.height).isEqualTo(expected)
        assertThat(pixels.argb.size).isEqualTo(pixels.width * pixels.height)
        val left = pixels.width / 4
        val right = pixels.width - 1 - left
        val top = pixels.height / 4
        val bottom = pixels.height - 1 - top
        assertThat(pixels.rgb(left, top)).isEqualTo(TopLeft)
        assertThat(pixels.rgb(right, top)).isEqualTo(TopRight)
        assertThat(pixels.rgb(left, bottom)).isEqualTo(BottomLeft)
        assertThat(pixels.rgb(right, bottom)).isEqualTo(BottomRight)
    }

    @Test
    fun `a large page is brought to the working size on its long side`() {
        assertDecoded(3000, 4000, expected = 1200 to WorkingSide)
    }

    @Test
    fun `a wide page has its width at the working size`() {
        assertDecoded(4000, 2000, expected = WorkingSide to 800)
    }

    @Test
    fun `a small page is kept as it is`() {
        assertDecoded(600, 900, expected = 600 to 900)
    }

    @Test
    fun `a page of the working size is kept as it is`() {
        assertDecoded(1200, WorkingSide, expected = 1200 to WorkingSide)
    }

    @Test
    fun `a page just over the working size is brought to it exactly`() {
        assertDecoded(WorkingSide + 1, 1000, expected = WorkingSide to 999)
    }

    @Test
    fun `a page just under twice the working size is brought to it exactly`() {
        assertDecoded(2000, 2 * WorkingSide - 1, expected = 1000 to WorkingSide)
    }

    @Test
    fun `a page of twice the working size is halved to it exactly`() {
        assertDecoded(2 * WorkingSide, 2000, expected = WorkingSide to 1000)
    }

    @Test
    fun `a very long strip keeps its shape`() {
        assertDecoded(10000, 200, expected = WorkingSide to 32)
    }

    @Test
    fun `a picture of one pixel is that pixel`() {
        val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(BottomRight or Opaque) }

        val pixels = decoded(file(one))

        assertThat(pixels.width to pixels.height).isEqualTo(1 to 1)
        assertThat(pixels.rgb(0, 0)).isEqualTo(BottomRight)
    }

    @Test
    fun `a margin that lets through reads white, what is opaque keeps its colour`() {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        Canvas(bitmap).drawRect(100f, 100f, 300f, 500f, Paint().apply { color = TopLeft or Opaque })

        val pixels = decoded(file(bitmap))

        assertThat(pixels.rgb(0, 0)).isEqualTo(Rgb)
        assertThat(pixels.rgb(50, 300)).isEqualTo(Rgb)
        assertThat(pixels.rgb(399, 599)).isEqualTo(Rgb)
        assertThat(pixels.rgb(200, 300)).isEqualTo(TopLeft)
        assertThat(pixels.argb.all { it ushr 24 == 0xFF }).isTrue()
    }

    @Test
    fun `what lets half through reads half its colour, half white`() {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        Canvas(bitmap).drawRect(100f, 100f, 300f, 500f, Paint().apply { color = TopLeft or (HalfThrough shl 24) })

        val pixel = decoded(file(bitmap)).rgb(200, 300)

        // Red at 128 of 255 over white: red stays full, green and blue come to 127 of white's 255.
        assertThat(pixel shr 16).isIn(254..255)
        assertThat(pixel shr 8 and 0xFF).isIn(126..128)
        assertThat(pixel and 0xFF).isIn(126..128)
    }

    @Test
    fun `a margin that lets through reads white on a page brought to the working size too`() {
        val bitmap = Bitmap.createBitmap(2000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        Canvas(bitmap).drawRect(500f, 500f, 1500f, 2500f, Paint().apply { color = BottomLeft or Opaque })

        val pixels = decoded(file(bitmap))

        assertThat(pixels.rgb(0, 0)).isEqualTo(Rgb)
        assertThat(pixels.rgb(pixels.width / 2, pixels.height / 2)).isEqualTo(BottomLeft)
    }

    @Test
    fun `a file that is no picture is no picture`() {
        val file = Files.createTempFile("page", ".bin").toFile().apply { writeText("no picture") }

        assertThat(decodeForCut(file)).isEqualTo(PageDecoding.NoPicture)
    }

    @Test
    fun `a file that is gone is for another time`() {
        val file = File(Files.createTempDirectory("pages").toFile(), "gone")

        assertThat(decodeForCut(file)).isEqualTo(PageDecoding.NotNow)
    }

    @Test
    fun `a picture that says nothing of its orientation, or that it is as stored, is cut`() {
        val plain = file(quarters(300, 400), Bitmap.CompressFormat.JPEG)
        val normal = file(quarters(300, 400), Bitmap.CompressFormat.JPEG).oriented(ExifInterface.ORIENTATION_NORMAL)

        assertThat(decodeForCut(plain)).isInstanceOf(PageDecoding.Picture::class.java)
        assertThat(decodeForCut(normal)).isInstanceOf(PageDecoding.Picture::class.java)
    }

    @Test
    fun `a picture to be turned before it is shown is not cut`() {
        val turned = file(quarters(300, 400), Bitmap.CompressFormat.JPEG).oriented(ExifInterface.ORIENTATION_ROTATE_90)

        assertThat(decodeForCut(turned)).isEqualTo(PageDecoding.NoPicture)
    }

    @Test
    fun `only a picture shown as it is stored is cut`() {
        val cut = (0..8).filter(::cutsWithOrientation)

        assertThat(cut).containsExactly(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
    }

    /** This file, saying its picture is to be shown in that [orientation]. */
    private fun File.oriented(orientation: Int): File = apply {
        ExifInterface(path).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }
}
