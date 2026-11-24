package app.tfdn.lirelle.core.comics.panels

/** A page of yes and no, row by row. */
internal class Plane(val width: Int, val height: Int, val bits: BooleanArray) {
    constructor(width: Int, height: Int) : this(width, height, BooleanArray(width * height))

    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]

    operator fun not(): Plane = Plane(width, height, BooleanArray(bits.size) { !bits[it] })

    /**
     * Each cell from the box [boxWidth] by [boxHeight] (odd sizes) centred on it: with [erode], set when the whole
     * box is, what is outside the plane counting as set; without, set when anything in the box is, the outside
     * counting as unset. One pass along the rows, one down the columns, and none along a side of one cell: a box
     * of one cell gives the plane itself.
     */
    fun boxed(boxWidth: Int, boxHeight: Int, erode: Boolean): Plane {
        val along = if (boxWidth > 1) rowsBoxed(boxWidth / 2, erode) else this
        return if (boxHeight > 1) along.columnsBoxed(boxHeight / 2, erode) else along
    }

    /** [boxed] along the rows only: a window of [radius] cells each side slides along each row. */
    private fun rowsBoxed(radius: Int, erode: Boolean): Plane {
        val out = Plane(width, height)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0 until minOf(radius, width)) if (bits[row + x]) count++
            for (x in 0 until width) {
                if (x + radius < width && bits[row + x + radius]) count++
                if (x - radius > 0 && bits[row + x - radius - 1]) count--
                val span = minOf(width - 1, x + radius) - maxOf(0, x - radius) + 1
                out.bits[row + x] = if (erode) count == span else count > 0
            }
        }
        return out
    }

    /**
     * [boxed] down the columns only, row by row: how many cells are set in each column within [radius] rows of the
     * current one is kept as the rows go by.
     */
    private fun columnsBoxed(radius: Int, erode: Boolean): Plane {
        val out = Plane(width, height)
        val counts = IntArray(width)
        for (y in 0 until minOf(radius, height)) countRow(counts, y, 1)
        for (y in 0 until height) {
            if (y + radius < height) countRow(counts, y + radius, 1)
            if (y - radius > 0) countRow(counts, y - radius - 1, -1)
            val span = minOf(height - 1, y + radius) - maxOf(0, y - radius) + 1
            val row = y * width
            for (x in 0 until width) out.bits[row + x] = if (erode) counts[x] == span else counts[x] > 0
        }
        return out
    }

    /** Adds [step] to the count of each column whose cell on the row [y] is set. */
    private fun countRow(counts: IntArray, y: Int, step: Int) {
        val row = y * width
        for (x in 0 until width) if (bits[row + x]) counts[x] += step
    }

    /** What is set and reaches the top left corner going by the sides of cells. Empty when that corner is unset. */
    fun floodedFromCorner(): Plane {
        val out = Plane(width, height)
        if (!bits[0]) return out
        val stack = IntArray(bits.size)
        var held = 0
        stack[held++] = 0
        out.bits[0] = true
        while (held > 0) {
            val at = stack[--held]
            val x = at % width
            val y = at / width
            if (x > 0) held = out.take(at - 1, this, stack, held)
            if (x < width - 1) held = out.take(at + 1, this, stack, held)
            if (y > 0) held = out.take(at - width, this, stack, held)
            if (y < height - 1) held = out.take(at + width, this, stack, held)
        }
        return out
    }

    /** Sets the cell [at] when [from] has it and it was not: it then waits on the [stack], [held] cells high. */
    private fun take(at: Int, from: Plane, stack: IntArray, held: Int): Int {
        if (!from.bits[at] || bits[at]) return held
        bits[at] = true
        stack[held] = at
        return held + 1
    }

    /**
     * The blobs of what is set, cells touching by a side or a corner being one blob, each with its box and its
     * cells. The plane is emptied as they are found: it is to be a copy nothing else needs.
     */
    fun takeBlobs(): List<Blob> {
        val walk = BlobWalk(this)
        val found = mutableListOf<Blob>()
        for (start in bits.indices) if (bits[start]) found += walk.blobFrom(start)
        return found
    }
}

/**
 * The walk over the blobs of a [plane]. A cell is taken off the plane when it is met, and waits in a queue for its
 * neighbours to be looked at: no cell is met twice, so the queue never starts over, and the cells of a blob are
 * those that entered it while the blob was walked.
 */
private class BlobWalk(private val plane: Plane) {
    private val queue = IntArray(plane.bits.size)
    private var tail = 0

    /** Takes off the plane the blob the cell [start] is in, and gives it. */
    fun blobFrom(start: Int): Blob {
        val width = plane.width
        val first = tail
        var head = tail
        var left = width
        var top = plane.height
        var right = 0
        var bottom = 0
        take(start, start)
        while (head < tail) {
            val at = queue[head++]
            val x = at % width
            val y = at / width
            if (x < left) left = x
            if (x >= right) right = x + 1
            if (y < top) top = y
            if (y >= bottom) bottom = y + 1
            val from = maxOf(0, x - 1)
            val to = minOf(width - 1, x + 1)
            for (row in maxOf(0, y - 1)..minOf(plane.height - 1, y + 1)) take(row * width + from, row * width + to)
        }
        return Blob(Box(left, top, right, bottom), queue, first, tail)
    }

    /** Takes off the plane the cells set from [first] to [last]: they wait for their neighbours. */
    private fun take(first: Int, last: Int) {
        for (next in first..last) {
            if (plane.bits[next]) {
                plane.bits[next] = false
                queue[tail++] = next
            }
        }
    }
}

/**
 * A blob of a plane: its [box], and its cells, which are those of [queue] from [first] until [end], each as its
 * place in [Plane.bits].
 */
internal class Blob(val box: Box, val queue: IntArray, val first: Int, val end: Int) {
    /** Calls [each] with every cell of the blob, as its place in [Plane.bits]. */
    inline fun forEachCell(each: (Int) -> Unit) {
        for (i in first until end) each(queue[i])
    }
}

/** A rectangle of a plane, in pixels: [right] and [bottom] excluded. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** How many cells of a plane are set in any rectangle, in constant time. */
internal class Sums(plane: Plane) {
    private val width = plane.width + 1
    private val table = IntArray(width * (plane.height + 1))

    init {
        for (y in 0 until plane.height) {
            var row = 0
            for (x in 0 until plane.width) {
                if (plane.bits[y * plane.width + x]) row++
                table[(y + 1) * width + x + 1] = table[y * width + x + 1] + row
            }
        }
    }

    /** The cells set from [x0] to [x1] and [y0] to [y1], the ends excluded. */
    fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int =
        table[y1 * width + x1] - table[y0 * width + x1] - table[y1 * width + x0] + table[y0 * width + x0]
}

/**
 * How many cells of a plane are set on any stretch of one line, in constant time: of a row when [rows], of a
 * column otherwise. Half the room of [Sums], for what is only ever counted one whole line at a time. Counts made
 * along rows are read by [onRow] alone, those made along columns by [onColumn] alone: read the other way they
 * would give numbers that mean nothing, so that throws.
 */
internal class LineCounts(plane: Plane, private val rows: Boolean) {
    private val width = plane.width

    /** For each cell, the cells set on its line up to it, itself included. */
    private val running = CharArray(plane.bits.size)

    init {
        require(maxOf(plane.width, plane.height) <= Char.MAX_VALUE.code) { "a side too long to count on" }
        for (y in 0 until plane.height) {
            for (x in 0 until width) {
                val at = y * width + x
                val had = if (rows) upTo(y, x) else upTo(x, y)
                running[at] = (had + if (plane.bits[at]) 1 else 0).toChar()
            }
        }
    }

    /** The cells set on the [row], counted from the top, from the column [from] to the column [to] excluded. */
    fun onRow(row: Int, from: Int, to: Int): Int {
        require(rows) { "counted along columns: no row to read" }
        return count(row, from, to)
    }

    /** The cells set on the [column], counted from the left, from the row [from] to the row [to] excluded. */
    fun onColumn(column: Int, from: Int, to: Int): Int {
        require(!rows) { "counted along rows: no column to read" }
        return count(column, from, to)
    }

    /** The cells set on the [line] (a row from the top, or a column from the left) from [from] to [to] excluded. */
    private fun count(line: Int, from: Int, to: Int): Int = upTo(line, to) - upTo(line, from)

    /** The cells set on the [line] before its cell [end]. */
    private fun upTo(line: Int, end: Int): Int = when {
        end == 0 -> 0
        rows -> running[line * width + end - 1].code
        else -> running[(end - 1) * width + line].code
    }
}
