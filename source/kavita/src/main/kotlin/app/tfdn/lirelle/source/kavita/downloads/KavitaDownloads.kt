package app.tfdn.lirelle.source.kavita.downloads

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import okhttp3.Response

/** Where a Kavita profile's server serves its files, until its books are read and kept (#51): nowhere. */
internal class NoKavitaDownloadLocations : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = null
}

/** A Kavita server's refusal of a kept file, read from its status as its other refusals to the profile's key. */
internal class KavitaDownloadRefusals(private val errors: KavitaErrors = KavitaErrors()) : DownloadRefusals {
    override fun refusal(response: Response): ApiError = errors.status(response.code, KavitaCall.Authenticated)
}
