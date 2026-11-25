@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionError
import app.tfdn.lirelle.feature.player.service.LastBook
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How many books of Home are read to find the first one to continue: its sections come whole. */
private const val HomePage = 100

/** Nothing the car asked for can start; the car was told why. */
class NothingToStart(why: String) : Exception(why)

/**
 * What the session's player is set to when a car asks for something to play: a book it was listed and that was
 * tapped (`book/<id>`), words to search (a voice request, the first book found), or nothing named (the last
 * book, as a play button resumes it; else the first book to continue). The answer is an item that stands for
 * the book until the player has loaded it ([PlayerSessionContract.startItem]).
 *
 * When nothing can start, the answer fails and the car is told why, in a sentence.
 *
 * @param listed the book a car was listed under a media id, for its title.
 * @param closed whether the profile closed: nothing of it starts any more.
 * @param lastBook the profile's last book.
 */
class CarAsks(
    private val shelves: CarShelves,
    private val texts: CarTexts,
    private val scope: CoroutineScope,
    private val listed: (mediaId: String) -> CarNode.Book? = { null },
    private val closed: () -> Boolean = { false },
    private val lastBook: suspend () -> LastBook? = { null },
) {
    /** The car that asked last: the one told when its book could not be loaded. */
    private var asker: CarLink? = null

    /** The item to set for what [car] [asked]. Media3 waits for it: it always ends, with the item or a failure. */
    fun items(car: CarLink, asked: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        if (closed()) return future.apply { setException(NothingToStart("The profile closed")) }
        asker = car
        scope.launch {
            val item = runCatching { itemFor(car, asked.firstOrNull()) }
            item.onSuccess { future.set(listOf(it)) }.onFailure(future::setException)
        }.invokeOnCompletion { future.setException(NothingToStart("The service ended")) }
        return future
    }

    /** The book the car asked for last could not be loaded: said in the words of the phone's player. */
    fun failed() {
        asker?.say(SessionError(SessionError.ERROR_IO, texts.loadFailed()))
    }

    private suspend fun itemFor(car: CarLink, asked: MediaItem?): MediaItem {
        val query = asked?.requestMetadata?.searchQuery?.trim().orEmpty()
        val tapped = asked?.mediaId?.let { id -> CarIds.bookOf(id)?.let { it to listed(id)?.title } }
        return when {
            tapped != null -> PlayerSessionContract.startItem(tapped.first, tapped.second)
            query.isNotEmpty() -> found(car, query)
            else -> toResume(car)
        }
    }

    /** The first book found for [query]. */
    private suspend fun found(car: CarLink, query: String): MediaItem = when (val answer = shelves.search(query)) {
        is CarAnswer.Notice -> refuse(car, SessionError.ERROR_IO, texts.notice(answer.notice))
        is CarAnswer.Nodes -> answer.nodes.firstNotNullOfOrNull { it as? CarNode.Book }
            ?.let { PlayerSessionContract.startItem(it.id, it.title) }
            ?: refuse(car, SessionError.ERROR_BAD_VALUE, texts.searchNothing(query))
    }

    /** The last book, as a play button resumes it; else the first book of Home's "Continue". */
    private suspend fun toResume(car: CarLink): MediaItem {
        lastBook()?.let { return PlayerSessionContract.resumeItem(it) }
        val home = shelves.children(CarPlace.Tab.Home, 0, HomePage) as? CarAnswer.Nodes
        return home?.nodes?.firstNotNullOfOrNull { node ->
            (node as? CarNode.Book)?.takeIf { it.section == CarSection.Continue }
        }?.let { PlayerSessionContract.startItem(it.id, it.title) }
            ?: refuse(car, SessionError.ERROR_BAD_VALUE, texts.nothingToResume())
    }

    private fun refuse(car: CarLink, code: Int, sentence: String): Nothing {
        car.say(SessionError(code, sentence))
        throw NothingToStart(sentence)
    }
}
