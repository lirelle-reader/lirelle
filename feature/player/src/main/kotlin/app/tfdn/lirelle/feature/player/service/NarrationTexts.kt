package app.tfdn.lirelle.feature.player.service

import android.content.res.Resources
import app.tfdn.lirelle.feature.player.R

/** The words of the system controls for a narration, in the device's language. */
class NarrationTexts(private val resources: Resources) {
    /** "Section 12": a section without a title, by its number in the book. */
    fun section(number: Int): String = resources.getString(R.string.player_notification_section, number)

    fun finished(): String = resources.getString(R.string.player_notification_narration_finished)
}

/**
 * The title a narration's chapter at [index] shows, by [sectionTitle]. Null for an index the playlist does not
 * have.
 */
fun BookPlaylist.narrationChapterTitle(index: Int, texts: NarrationTexts): String? {
    val chapter = chapters.getOrNull(index) ?: return null
    return sectionTitle(chapter.title, chapter.sectionIndex, index, texts::section)
}

/**
 * The one rule for a narrated section's title, in the notification as on the app's screens: its own [title], or
 * when it has none "Section N", N its 1-based place in the book's reading order ([sectionIndex]; its [position]
 * among the narrated sections when that is not known), named by [untitled].
 */
fun sectionTitle(title: String, sectionIndex: Int?, position: Int, untitled: (number: Int) -> String): String =
    title.ifBlank { untitled((sectionIndex ?: position) + 1) }
