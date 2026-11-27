package app.tfdn.lirelle.source.kavita.downloads

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.kavita.library.kavitaFile
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import java.net.HttpURLConnection
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/**
 * Where a Kavita profile's server serves what is kept of its books: a comic's pages where the comics reader fetches
 * them ([pages], of the profile's server) — a download asks what reading asks — and a book's whole file (an EPUB, a
 * PDF) as Kavita downloads it, which needs the account's Download role, as every kept file does. Kavita has no
 * audio nor narration. The key goes with the profile's client.
 *
 * @param serverUrl the profile's server, without a trailing slash. Read on every call.
 */
internal class KavitaDownloadLocations(
    private val pages: PageSource,
    private val serverUrl: () -> String?,
) : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = when (source) {
        is FileSource.Page -> pages.pageUrl(source.kind, source.fileId, source.index)
        is FileSource.Whole -> source.fileId.kavitaFile()?.let { chapterDownload(it.chapterId) }
        is FileSource.Audioless, is FileSource.AudioAsset, is FileSource.ReadAlongTrack -> null
    }

    /** Kavita's download of chapter [chapterId]: its one file (a chapter of several files is never kept). */
    private fun chapterDownload(chapterId: Int): String? = serverUrl()?.toHttpUrlOrNull()?.newBuilder()
        ?.addPathSegments(ChapterDownload)
        ?.addQueryParameter("chapterId", chapterId.toString())
        ?.build()?.toString()
}

private const val ChapterDownload = "api/Download/chapter"

/**
 * A Kavita server's refusal of a kept file, read from its status as its other refusals to the profile's key. A file
 * refused (403) is the account's rights: its Download role taken away while it downloads, which the download says
 * is not allowed.
 */
internal class KavitaDownloadRefusals(private val errors: KavitaErrors = KavitaErrors()) : DownloadRefusals {
    override fun refusal(response: Response): ApiError = errors.status(response.code, KavitaCall.Authenticated)

    override fun failure(response: Response): DownloadFailure =
        if (response.code == HttpURLConnection.HTTP_FORBIDDEN) DownloadFailure.NotAllowed else super.failure(response)
}
