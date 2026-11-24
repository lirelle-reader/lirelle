package app.tfdn.lirelle.core.comics.panels

import javax.imageio.ImageIO

/** A test page of `src/test/resources/panels`, as the cut reads it. */
internal fun testPage(name: String): PagePixels {
    val stream = checkNotNull(PagePixels::class.java.getResourceAsStream("/panels/$name.png")) { "no page $name" }
    val image = stream.use(ImageIO::read)
    val argb = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
    return PagePixels(image.width, image.height, argb)
}

/** A page of one colour with rectangles on it: `(left, top, right, bottom) to rgb`. */
internal fun drawnPage(width: Int, height: Int, ground: Int, vararg boxes: Pair<List<Int>, Int>): PagePixels {
    val argb = IntArray(width * height) { ground or (0xFF shl 24) }
    for ((box, colour) in boxes) {
        for (y in box[1] until box[3]) for (x in box[0] until box[2]) argb[y * width + x] = colour or (0xFF shl 24)
    }
    return PagePixels(width, height, argb)
}
