package app.tfdn.lirelle.source.komga.downloads

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.komga.network.KomgaCall
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import java.net.HttpURLConnection
import okhttp3.Response

/**
 * Where a Komga profile's server serves what is kept of its books: a comic's pages, where the comics reader fetches
 * them ([pages], of the profile's server) — a download is the same request as reading it. Nothing else yet: no file
 * is fetched whole (3c), and Komga has no audio nor narration.
 */
internal class KomgaDownloadLocations(private val pages: PageSource) : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = when (source) {
        is FileSource.Page -> pages.pageUrl(source.kind, source.fileId, source.index)
        is FileSource.Whole, is FileSource.Audioless, is FileSource.AudioAsset, is FileSource.ReadAlongTrack -> null
    }
}

/**
 * A Komga server's refusal of a kept file, read from its status as its other refusals to the profile's key. A page
 * refused (403) is the account's rights: Komga serves pages to an account with `PAGE_STREAMING` only, and the
 * download says it is not allowed. A page not found (404) is of a book whose file Komga analyses again (a rescan
 * during the download: `/pages` answers 404 until the media is ready), not of a book gone, which the planner finds
 * before any page is asked: it fails as an unexpected error, which the user may retry. Reading has its own reading
 * of the refusals, which this one leaves as it is.
 */
internal class KomgaDownloadRefusals(private val errors: KomgaErrors = KomgaErrors()) : DownloadRefusals {
    override fun refusal(response: Response): ApiError = when (response.code) {
        HttpURLConnection.HTTP_NOT_FOUND -> ApiError.Unexpected
        else -> errors.status(response.code, KomgaCall.Authenticated)
    }

    override fun failure(response: Response): DownloadFailure =
        if (response.code == HttpURLConnection.HTTP_FORBIDDEN) DownloadFailure.NotAllowed else super.failure(response)
}
