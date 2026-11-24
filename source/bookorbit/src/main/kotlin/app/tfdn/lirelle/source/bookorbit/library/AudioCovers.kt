package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.MediumCover
import java.net.URLEncoder

private const val AudioMedium = "audio"

/**
 * The URLs of the audiobook's own cover of a book, against the session's server: the server keeps one
 * cover per medium, and serves the audio one with `medium=audio`. Its version is part of the URL, so a
 * changed cover is a new cache entry.
 */
internal class AudioCovers(private val serverUrl: String) {

    /** The audio cover of the book BookOrbit numbers [bookNumber], whose version is [version]. */
    fun of(bookNumber: Long, version: String?) = MediumCover(
        url = url(bookNumber, "cover", version),
        thumbnailUrl = url(bookNumber, "thumbnail", version),
    )

    private fun url(bookNumber: Long, image: String, version: String?): String =
        "$serverUrl/api/v1/books/$bookNumber/$image?medium=$AudioMedium" +
            (version?.takeIf { it.isNotEmpty() }?.let { "&t=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
}
