package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.fetch.ImageSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DrawSizeTest {

    @Test
    fun `a portrait page is as high as the long side asked`() {
        assertThat(pixelSize(ImageSize(300, 450), longSidePx = 2048)).isEqualTo(ImageSize(1365, 2048))
    }

    @Test
    fun `a page wider than high is as wide as the long side asked`() {
        assertThat(pixelSize(ImageSize(600, 300), longSidePx = 2048)).isEqualTo(ImageSize(2048, 1024))
    }

    @Test
    fun `a square page has the long side on both sides`() {
        assertThat(pixelSize(ImageSize(200, 200), longSidePx = 4096)).isEqualTo(ImageSize(4096, 4096))
    }

    @Test
    fun `a ribbon of a page keeps a pixel of its short side`() {
        assertThat(pixelSize(ImageSize(10_000, 1), longSidePx = 2048)).isEqualTo(ImageSize(2048, 1))
    }

    @Test
    fun `a page without a size is drawn as a square rather than not at all`() {
        assertThat(pixelSize(ImageSize(0, 0), longSidePx = 2048)).isEqualTo(ImageSize(2048, 2048))
    }

    @Test
    fun `the long side asked is bounded by the first drawing and by the page view's limit`() {
        assertThat(boundedLongSide(100)).isEqualTo(2048)
        assertThat(boundedLongSide(2048)).isEqualTo(2048)
        assertThat(boundedLongSide(3000)).isEqualTo(3000)
        assertThat(boundedLongSide(4096)).isEqualTo(4096)
        assertThat(boundedLongSide(9000)).isEqualTo(4096)
    }

    @Test
    fun `a page that was never drawn needs drawing`() {
        assertThat(needsDrawing(keptLongSide = null, askedLongSide = 2048)).isTrue()
    }

    @Test
    fun `a page is drawn again only for a larger size than the one kept`() {
        assertThat(needsDrawing(keptLongSide = 2048, askedLongSide = 3000)).isTrue()
        assertThat(needsDrawing(keptLongSide = 3000, askedLongSide = 3000)).isFalse()
        assertThat(needsDrawing(keptLongSide = 3000, askedLongSide = 2048)).isFalse()
    }

    @Test
    fun `a page kept at the limit is not drawn again, whatever is asked`() {
        assertThat(needsDrawing(keptLongSide = 4096, askedLongSide = 9000)).isFalse()
        assertThat(needsDrawing(keptLongSide = 2048, askedLongSide = 9000)).isTrue()
    }
}
