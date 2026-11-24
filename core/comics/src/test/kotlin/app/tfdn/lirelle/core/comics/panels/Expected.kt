package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction

/** The reference's answers for the test pages: `name;ltr|rtl;l,t,r,b,P|W …`. */
internal fun expectedZones(name: String, direction: Direction): List<Zone> {
    val way = if (direction == Direction.RightToLeft) "rtl" else "ltr"
    val stream = checkNotNull(PagePixels::class.java.getResourceAsStream("/panels/expected.txt"))
    val text = stream.bufferedReader().use { it.readText() }
    val line = text.lines().first { it.startsWith("$name;$way;") }
    return line.substringAfter("$name;$way;").split(' ').filter { it.isNotBlank() }.map {
        val fields = it.split(',')
        val kind = if (fields[4] == "P") ZoneKind.Filled else ZoneKind.Sparse
        Zone(fields[0].toFloat(), fields[1].toFloat(), fields[2].toFloat(), fields[3].toFloat(), kind)
    }
}
