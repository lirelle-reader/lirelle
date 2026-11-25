package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Says which tabs no longer list what a car was shown, for as long as this scope lasts: a car keeps what it
 * was listed, and asks again only for a tab it is told has [changed]. The tabs the server fills change when
 * the profile's sign-in becomes needed or is made ([signInNeeded]); Downloaded when a book's audio is kept, or
 * no longer ([keptAudio]). What the server itself gains or loses is seen when the car opens the tab again.
 */
internal fun CoroutineScope.refreshCars(
    signInNeeded: Flow<Boolean>,
    keptAudio: Flow<Set<BookId>>,
    changed: (CarPlace.Tab) -> Unit,
) {
    // The first value of each is what the car is listed anyway.
    launch {
        signInNeeded.distinctUntilChanged().drop(1).collect {
            (CarPlace.Tab.entries - CarPlace.Tab.Downloaded).forEach(changed)
        }
    }
    launch { keptAudio.distinctUntilChanged().drop(1).collect { changed(CarPlace.Tab.Downloaded) } }
}
