package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.downloads.offline.WaitingBookData
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.namesKeptFiles
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import javax.inject.Inject

/**
 * Where what a plan learns besides the files goes, by what the plan is for. A download keeps it with the book.
 * A check and an update look at a format the device has whole, in the version it was fetched: what that copy
 * opens with, its manifest, its map and a page that names its files, stays as it is. A check stores none of
 * it; an update puts it beside the kept copy, to take its place with the files.
 */
internal class PlannedData @Inject constructor(
    private val kept: KeptBookData,
    private val waiting: WaitingBookData,
    private val registry: DownloadRegistry,
) {
    /**
     * The book's page, read for a plan of [format]. A page that names the files the device keeps is kept at
     * once, whatever the plan is for: the kept copy opens with it as well. The page of a download is kept, with
     * the files of the kept one when only that one names the kept files ([KeptBookData.keep]).
     */
    suspend fun page(detail: BookDetail, format: DownloadFormat, purpose: PlanFor) {
        when {
            purpose == PlanFor.Download || registry.namesKeptFiles(detail) -> kept.keep(detail)
            purpose == PlanFor.Update -> waiting.keepPage(detail, format)
            else -> Unit
        }
    }

    /** False when a manifest that is needed could not be written. */
    suspend fun manifest(manifest: AudioManifest, purpose: PlanFor): Boolean = when (purpose) {
        PlanFor.Download -> true.also { kept.keepManifest(manifest) }
        PlanFor.Check -> true
        PlanFor.Update -> waiting.keepManifest(manifest)
    }

    /** False when a map that is needed could not be had or written. */
    suspend fun map(bookId: BookId, fileId: FileId, version: String?, purpose: PlanFor): Boolean = when (purpose) {
        PlanFor.Download -> kept.keepMap(bookId, fileId, version)
        PlanFor.Check -> true
        PlanFor.Update -> waiting.keepMap(bookId, fileId, version)
    }
}
