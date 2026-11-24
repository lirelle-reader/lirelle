package app.tfdn.lirelle.core.reader.marks

private val Wrapped = Regex("""epubcfi\((.*)\)""")
private val Assertions = Regex("""\[[^\]]*]""")

/** The steps of a path, then its character offset: `/6/4!/4/2/1:5`, the rest (`~`, `@`) ignored. */
private val Path = Regex("""^((?:/\d+!?)+)(?::(\d+))?""")
private val Step = Regex("""/(\d+)""")
private val SpineStep = Regex("""^/6/(\d+)!""")

/** The package's spine is its third child: a spine item `N` is at step `/6/(2N + 2)`. */
private const val StepsPerItem = 2

/** Reading order by the numeric steps of the CFIs; a range by its start; malformed and null last. */
object CfiOrder : Comparator<String?> {
    override fun compare(a: String?, b: String?): Int {
        val first = a?.let(::stepsOf)
        val second = b?.let(::stepsOf)
        return when {
            first == null && second == null -> 0
            first == null -> 1
            second == null -> -1
            else -> compareSteps(first, second)
        }
    }

    private fun compareSteps(a: List<Long>, b: List<Long>): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return a.size.compareTo(b.size)
    }
}

/** The spine index of a CFI: `/6/N!` gives `N / 2 - 1`. Null when there is none. */
fun spineIndexOf(cfi: String): Int? {
    val step = startOf(cfi)?.let { SpineStep.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return if (step >= StepsPerItem && step % StepsPerItem == 0) step / StepsPerItem - 1 else null
}

/** The path a CFI starts at, without assertions: a range's parent and start joined. */
private fun startOf(cfi: String): String? {
    val inner = Wrapped.matchEntire(cfi)?.groupValues?.get(1)?.replace(Assertions, "") ?: return null
    val parts = inner.split(',')
    return if (parts.size > 1) parts[0] + parts[1] else inner
}

/** The numbers to compare: every step, then the offset if there is one. Null when malformed. */
private fun stepsOf(cfi: String): List<Long>? {
    val path = startOf(cfi)?.let { Path.find(it) } ?: return null
    val steps = Step.findAll(path.groupValues[1]).toList().map { it.groupValues[1].toLongOrNull() ?: return null }
    val offset = path.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
    return steps + listOfNotNull(offset)
}
