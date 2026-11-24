package app.tfdn.lirelle.core.comics.di

import app.tfdn.lirelle.core.comics.ComicPagesOpener
import app.tfdn.lirelle.core.comics.DefaultComicPagesOpener
import app.tfdn.lirelle.core.comics.DefaultServedPages
import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.comics.ServedPages
import app.tfdn.lirelle.core.comics.kept.KeptCopies
import app.tfdn.lirelle.core.comics.panels.PagePanelsOpener
import app.tfdn.lirelle.core.comics.pdf.PdfPagesOpener
import app.tfdn.lirelle.core.comics.pdf.PdfParts
import app.tfdn.lirelle.core.comics.pdf.RendererDocument
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.Dispatchers

/**
 * What opens a book read by pages for one profile: it looks at the profile's downloads and at its reader's
 * cache before the server, the device's own drawing among them. The pages come from the profile's source
 * ([PageSource]), for the reader and for the downloads of that profile.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ComicPagesProfileModule {

    /** What the downloads ask of the books served by pages; the pages seen are the reader's store's. */
    @Provides
    @ProfileScoped
    fun servedPages(store: ComicStore, source: PageSource): ServedPages =
        DefaultServedPages(store = store, source = source)

    /** The pages the downloads keep of an album are read before the server is asked for any. */
    @Provides
    fun keptCopies(downloaded: DownloadedFiles, holds: BookHolds): KeptCopies = KeptCopies(downloaded, holds)

    @Provides
    @ProfileScoped
    fun opener(store: ComicStore, source: PageSource, cache: ReaderCache, kept: KeptCopies): ComicPagesOpener =
        DefaultComicPagesOpener(
            store = store,
            source = source,
            pdf = PdfPagesOpener(PdfParts(store, cache, ::RendererDocument, Dispatchers.IO)),
            copies = kept,
            io = Dispatchers.IO,
        )
}

/** The page reader's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ComicPagesProfileEntry {
    fun comicPagesOpener(): ComicPagesOpener

    /** What finds the panels of the pages of an album that is open. */
    fun pagePanelsOpener(): PagePanelsOpener

    /** The whole settings of the reader, the profile's reading directions among them. */
    fun comicSettingsStore(): ComicSettingsStore
}

/**
 * Gives view models the page reader's objects of the open profile. Unscoped, and in no component that
 * outlives a profile: each view model gets those of the profile open when it is made. View models only: a
 * bridge may answer with the profile closed last, which a service must never act on.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ComicPagesBridges {

    @Provides
    fun opener(current: CurrentProfile): ComicPagesOpener = current.entry<ComicPagesProfileEntry>().comicPagesOpener()

    @Provides
    fun panelsOpener(current: CurrentProfile): PagePanelsOpener =
        current.entry<ComicPagesProfileEntry>().pagePanelsOpener()

    @Provides
    fun settings(current: CurrentProfile): ComicSettingsStore =
        current.entry<ComicPagesProfileEntry>().comicSettingsStore()
}
