@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the car's browser asks of a profile: what a place lists, and what a search finds ([CarLibrary]). */
interface CarShelves {
    suspend fun children(place: CarPlace, page: Int, pageSize: Int): CarAnswer

    suspend fun search(text: String): CarAnswer
}

/** One car connected to the session: who it is, and what it can be told besides the answers to its questions. */
interface CarLink {
    /** The car itself, the same at each of its questions: what is kept for it is kept by this. */
    val controller: Any

    /** Tells the car an error of the session, which is how it is given a sentence to show. */
    fun say(error: SessionError)

    /** Tells the car that the search of [query] has [count] results to ask for. */
    fun searchChanged(query: String, count: Int, params: LibraryParams?)
}

/** How many of the items the cars were listed are remembered: more than a car is listed in a visit. */
private const val ListedBound = 2_000

/** The search a car asked last: it asks for its result afterwards, page by page. */
private class Search(val query: String, val answer: CarAnswer)

/**
 * The answers to a car that browses: each question of Media3's library callbacks, answered from the profile's
 * [library] as the [items] a car draws. It knows nothing of who may ask: the session's callback lets cars
 * through, and no one else.
 *
 * The answers are read in [scope], the service's, and arrive on its thread. Once the profile [closed], nothing
 * of it is listed any more.
 *
 * @param notices how a car is told why a place lists nothing.
 */
class CarBrowser(
    private val library: CarShelves,
    private val items: CarItems,
    private val notices: CarNotices,
    private val scope: CoroutineScope,
    private val closed: () -> Boolean = { false },
) {
    /**
     * What the cars were listed, by media id: a car that asks for one item names a book or a series it was
     * shown, which the library cannot be asked for by its id. The oldest go past [ListedBound].
     */
    private val listed = object : LinkedHashMap<String, CarNode>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CarNode>): Boolean = size > ListedBound
    }

    /** The last search of each car. */
    private val searches = mutableMapOf<Any, Search>()

    fun root(): ListenableFuture<LibraryResult<MediaItem>> =
        answering { LibraryResult.ofItem(items.root(), items.rootParams) }

    /** What the place [parentId] names lists: page [page], numbered from 0, of pages of [pageSize]. */
    fun children(
        car: CarLink,
        parentId: String,
        page: Int,
        pageSize: Int,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answering {
        val place = CarIds.placeOf(parentId) ?: return@answering LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        answered(library.children(place, page, pageSize), car)
    }

    /** The root, a tab, or a book or a series a car was listed. */
    fun item(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = answering {
        val item = when (val place = CarIds.placeOf(mediaId)) {
            CarPlace.Root -> items.root()
            is CarPlace.Tab -> items.of(CarNode.Tab(place))
            else -> listed[mediaId]?.let(items::of)
        }
        item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    /**
     * Searches [query], keeps what was found for [car], and tells it how many there are to ask for. A search
     * that could not be run counts as one: the car asks for it, and is told why.
     */
    fun search(car: CarLink, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        answering {
            val answer = library.search(query)
            searches[car.controller] = Search(query, answer)
            // The first one found may be asked by its id without the list being read.
            remember(answer)
            // Media3 refuses to tell of a search without words; the library found nothing for it.
            if (query.isNotEmpty()) car.searchChanged(query, (answer as? CarAnswer.Nodes)?.nodes?.size ?: 1, params)
            LibraryResult.ofVoid()
        }

    /** A page of what the search of [query] found: the search kept for [car], else searched now. */
    fun searchResult(
        car: CarLink,
        query: String,
        page: Int,
        pageSize: Int,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answering {
        val kept = searches[car.controller]?.takeIf { it.query == query }?.answer ?: library.search(query)
        answered(if (kept is CarAnswer.Nodes) CarAnswer.Nodes(kept.nodes.pageOf(page, pageSize)) else kept, car)
    }

    /** The book a car was listed under [mediaId]; null when none was. */
    fun listedBook(mediaId: String): CarNode.Book? = listed[mediaId] as? CarNode.Book

    /** The car [controller] left: its search is no longer kept. */
    fun forget(controller: Any) {
        searches -= controller
    }

    /** A list as the car is handed it; why there is none, as [notices] say it. */
    private fun answered(answer: CarAnswer, car: CarLink): LibraryResult<ImmutableList<MediaItem>> = when (answer) {
        is CarAnswer.Notice -> notices.answer(answer.notice, car)
        is CarAnswer.Nodes -> {
            remember(answer)
            LibraryResult.ofItemList(answer.nodes.map(items::of), null)
        }
    }

    private fun remember(answer: CarAnswer) {
        if (answer is CarAnswer.Nodes) answer.nodes.forEach { listed[CarIds.of(it)] = it }
    }

    /**
     * The answer of [ask], read in the service's scope. Media3 waits for it, so there always is one: a profile
     * that closed, or a service that ended before the answer, is an error.
     */
    private fun <T : Any> answering(ask: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        val gone = { future.set(LibraryResult.ofError<T>(SessionError.ERROR_INVALID_STATE)) }
        if (closed()) gone() else scope.launch { future.set(ask()) }.invokeOnCompletion { gone() }
        return future
    }
}
