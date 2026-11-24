package app.tfdn.lirelle.source.bookorbit.library.testing

object Fixtures {
    fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing fixture $name" }.readText()

    fun recorded(name: String): String = read("recorded/$name.json")
}
