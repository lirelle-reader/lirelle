package app.tfdn.lirelle.core.audio.timeline

import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest

private val Digits = Regex("""\d+""")

/**
 * Whether the book is in parts rather than in chapters: it has chapters, and none has a title of
 * its own. That is when every title is blank, or when the chapters are only the files, named alike.
 *
 * - The chapters are the files when there are as many as files and each one starts at the start
 *   of a file of its own. Real chapters ("Chapitre 1"… inside one file) keep their titles.
 * - Files are named alike when there are at least two, each name holds a number, and replacing
 *   every run of digits leaves the same text for all ("Part 1 - The Harbour Ledger (1993)",
 *   "Part 2 - The Harbour Ledger (1993)").
 */
fun chaptersAreParts(manifest: AudioManifest): Boolean {
    val titles = manifest.chapters.map { it.title }
    if (titles.isEmpty()) return false
    return titles.all { it.isBlank() } || (chaptersAreFiles(manifest, manifest.chapters) && namedAlike(titles))
}

/**
 * The titles of the book's chapters, in book order: "Part 1", "Part 2"… for a book in parts
 * ([chaptersAreParts]); otherwise the server's titles.
 *
 * Parts are numbered by position (1-based) through [partLabel], whatever number the titles carry.
 */
fun displayChapterTitles(manifest: AudioManifest, partLabel: (Int) -> String): List<String> {
    val titles = manifest.chapters.sortedBy { it.startMs }.map { it.title }
    return if (chaptersAreParts(manifest)) titles.indices.map { partLabel(it + 1) } else titles
}

private fun chaptersAreFiles(manifest: AudioManifest, chapters: List<AudioChapter>): Boolean =
    chapters.size == manifest.assets.size &&
        chapters.all { it.assetOffsetMs == 0L } &&
        chapters.map { it.assetId }.toSet() == manifest.assets.map { it.assetId }.toSet()

private fun namedAlike(titles: List<String>): Boolean =
    titles.size >= 2 &&
        titles.all { Digits.containsMatchIn(it) } &&
        titles.map { it.replace(Digits, "#") }.distinct().size == 1
