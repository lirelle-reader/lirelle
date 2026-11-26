package app.tfdn.lirelle.source.kavita.testing

import app.tfdn.lirelle.source.kavita.library.KavitaTitles

/** The titles of a book Kavita knows no title of, as the French strings write them, without Android. */
internal object FakeTitles : KavitaTitles {
    override fun volume(number: String): String = "Tome $number"

    override fun chapter(number: String): String = "Chapitre $number"

    override fun untitled(): String = "Sans titre"
}
