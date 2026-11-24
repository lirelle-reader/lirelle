package app.tfdn.lirelle.source.bookorbit.downloads

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.SourceOf
import javax.inject.Inject

/**
 * Where a BookOrbit server serves each kept file, by the routes its reader, its player and its page reader
 * fetch the same content by: a download is the same request as reading it. Of the profile's server, as its
 * repositories and its pages are. A page of a kind the server serves no pages of has no address: its file is
 * fetched whole, if at all.
 */
internal class BookOrbitDownloadLocations @Inject constructor(
    @param:SourceOf(ServerKind.BookOrbit) private val reader: ReaderRepository,
    @param:SourceOf(ServerKind.BookOrbit) private val audio: AudioRepository,
    @param:SourceOf(ServerKind.BookOrbit) private val readAlong: ReadAlongRepository,
    @param:SourceOf(ServerKind.BookOrbit) private val pages: PageSource,
) : DownloadLocations {
    override fun url(bookId: BookId, source: FileSource): String? = when (source) {
        is FileSource.Whole -> reader.fileUrl(source.fileId, audioless = false)
        is FileSource.Audioless -> reader.fileUrl(source.fileId, audioless = true)
        is FileSource.Page -> pages.pageUrl(source.kind, source.fileId, source.index)
        is FileSource.AudioAsset -> audio.assetUrl(bookId, source.assetId)
        is FileSource.ReadAlongTrack -> readAlong.trackUrl(bookId, source.fileId, source.href)
    }
}
