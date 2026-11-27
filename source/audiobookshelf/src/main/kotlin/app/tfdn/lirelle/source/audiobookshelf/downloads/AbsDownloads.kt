package app.tfdn.lirelle.source.audiobookshelf.downloads

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.audiobookshelf.network.AbsCall
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import okhttp3.Response

/** Where an Audiobookshelf profile's server serves its files, until its books are kept (#68, #69): nowhere. */
internal class NoAbsDownloadLocations : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = null
}

/**
 * An Audiobookshelf server's refusal of a kept file, read from its status as its other refusals to the profile's
 * access token.
 */
internal class AbsDownloadRefusals(private val errors: AbsErrors = AbsErrors()) : DownloadRefusals {
    override fun refusal(response: Response): ApiError = errors.status(response.code, AbsCall.Authenticated)
}
