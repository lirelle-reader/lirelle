package app.tfdn.lirelle.source.kavita.library

import android.content.Context
import app.tfdn.lirelle.source.kavita.R

/** The titles Lirelle gives a book Kavita knows no title of, in the app's language. */
internal interface KavitaTitles {
    /** A volume's, with its [number]: "Tome 3". */
    fun volume(number: String): String

    /** A chapter's, with its [number] or its range: "Chapitre 12". */
    fun chapter(number: String): String

    /** A book's that has neither a number nor a title to go by. */
    fun untitled(): String
}

/** The titles from the module's strings, in the language the app is shown in. */
internal class ResourceKavitaTitles(private val context: Context) : KavitaTitles {
    override fun volume(number: String): String = context.getString(R.string.kavita_volume_title, number)

    override fun chapter(number: String): String = context.getString(R.string.kavita_chapter_title, number)

    override fun untitled(): String = context.getString(R.string.kavita_untitled)
}
