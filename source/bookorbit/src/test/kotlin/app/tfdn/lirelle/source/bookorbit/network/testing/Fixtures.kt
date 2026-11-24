package app.tfdn.lirelle.source.bookorbit.network.testing

/**
 * The answers of sign-in and of the account: written by hand from the upstream types (`handmade/`), or recorded
 * from a server by `scripts/record-fixtures.sh` when [read] is given `recorded/…`.
 */
object Fixtures {
    private const val Recorded = "recorded/"

    fun read(name: String): String {
        val path = if (name.startsWith(Recorded)) name else "handmade/$name"
        return checkNotNull(javaClass.classLoader?.getResource("fixtures/$path")) { "Missing fixture $name" }
            .readText()
    }
}
