package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.source.audiobookshelf.library.AbsBookList
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProgress
import app.tfdn.lirelle.source.audiobookshelf.library.AbsWholeLibrary
import java.time.Instant
import mockwebserver3.MockWebServer

/** The recorded server's library of books. */
internal const val LivresId = "e70c1745-3cd9-4d47-bc40-6790ca8174d2"

/** The recorded server's library of comics. */
internal const val BdId = "daba50e0-b3bf-480f-b04e-9a0687f439ff"

internal val Livres = ServerLibrary(LibraryId(LivresId), "Livres")

/** How many books a page holds in the tests: the recorded list of 40 is 8 pages. */
internal const val TestPageSize = 5

/**
 * The Books list of the profile's server on [server], its pages of [TestPageSize] books, the account's progress read
 * once (the clock does not move), the whole lists kept in [whole] as the profile keeps them.
 */
internal fun bookListOn(server: MockWebServer, whole: AbsWholeLibrary = AbsWholeLibrary()): AbsBookList {
    val profileServer = libraryServer(server)
    val progress = AbsProgress(read = { profileServer.remote().api.me().mediaProgress }, now = { Instant.EPOCH })
    return AbsBookList(profileServer, progress, whole, pageSize = TestPageSize)
}

/**
 * [routes] answer the recorded libraries, the reader's progress, the recorded lists of Livres and BD, Livres'
 * recorded genres and two of BD's.
 */
internal fun answerRecordedLibrary(routes: LibraryRoutes) {
    routes.answer("/api/libraries", fixtureBody("libraries"))
    routes.answer("/api/me", fixtureBody("me"))
    routes.answer(libraryPath(LivresId), fixtureBody("filterdata-livres"))
    routes.answer(libraryPath(BdId), """{"filterdata": {"genres": ["Humour", "Bande dessinée"]}}""")
    routes.answer(itemsPath(LivresId), pagedItems("items-livres-all"))
    routes.answer(itemsPath(BdId), pagedItems("items-bd-added"))
}
