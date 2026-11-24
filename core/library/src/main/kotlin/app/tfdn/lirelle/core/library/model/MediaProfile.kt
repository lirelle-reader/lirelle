package app.tfdn.lirelle.core.library.model

/** What a book offers: something to read, something to listen to, both, or both in sync. */
enum class MediaProfile {
    /** Any non-audio format: EPUB, PDF, comics… */
    TextOnly,
    AudioOnly,

    /** A text file and an audio file, not synchronised. */
    TextAndAudio,

    /** An EPUB whose narration is synchronised with its text. */
    ReadAlong,
}

/**
 * The part of a file the rule needs.
 *
 * @property isContent whether the file is something to read or listen to, as the source decides; a cover image
 *   or a sidecar is not.
 */
data class MediaFile(val format: String?, val isContent: Boolean, val hasMediaOverlay: Boolean)

private val AudioFormats = setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")

fun isAudioFormat(format: String?): Boolean = format?.lowercase() in AudioFormats

/**
 * A book's media are derived from its files, when the server sends no "has audio" or "read-along" flag.
 * A text file is any content file that is not audio, formats the app does not know included.
 */
fun mediaProfileOf(files: List<MediaFile>): MediaProfile {
    val content = files.filter { it.isContent }
    val hasReadAlong = content.any { it.format?.lowercase() == "epub" && it.hasMediaOverlay }
    val hasAudio = content.any { isAudioFormat(it.format) }
    val hasText = content.any { !isAudioFormat(it.format) }
    return when {
        hasReadAlong -> MediaProfile.ReadAlong
        hasText && hasAudio -> MediaProfile.TextAndAudio
        hasAudio -> MediaProfile.AudioOnly
        else -> MediaProfile.TextOnly
    }
}
