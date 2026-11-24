package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileDataFormats
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * How a profile's objects are made and reached, and where they run. Hilt's in the application; a test gives
 * its own, and needs no Hilt.
 *
 * @property build makes the objects of a profile from its session, its folders and its scope.
 * @property lifeOf what those objects start and close.
 * @property warn told of what went wrong while closing a profile and stops nothing: a closer that failed or
 *   did not end, folders that could not be deleted. Never given an id, an address or an account.
 * @property graph where a profile's objects run.
 * @property io where folders are deleted.
 */
internal class GraphMaking(
    val build: (ProfileSession, ProfileFolders, CoroutineScope) -> ProfileComponent,
    val lifeOf: (ProfileComponent) -> ProfileLifeEntry,
    val warn: (message: String, failure: Throwable?) -> Unit,
    val graph: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Holds the objects of the current profile: builds them when a profile becomes current, and throws them away
 * when it stops being so. One profile is open at a time: going to another closes the one left, which sleeps
 * from then on, whole in its folders, and the screens are shown a wait meanwhile ([ShownSession.switching]).
 *
 * Removing a profile ends here ([ProfileDisposal]): its objects closed, then its folders deleted, then its
 * record dropped. In that order, so that nothing writes into a folder being deleted, and so that an
 * interruption leaves a profile still listed, whose removal the next start finishes. A profile removed while
 * it sleeps has no objects to close: its folders go as soon as the store lists it as being removed. The
 * picture of its account goes with its folders; a profile that is only left, or that waits for a sign-in,
 * keeps both.
 *
 * A profile whose data is of an older format starts again from empty folders when it is opened, at the start
 * as at a switch, before its objects are built ([ProfileGraphFactory.open]); one whose folders cannot be set
 * aside is not opened at this start, and the profile used before it is opened in its place.
 */
@Singleton
class ProfileGraphs @Inject constructor(
    private val store: ProfileStore,
    private val sessions: SessionStore,
    private val roots: ProfileRoots,
    private val avatars: ProfileAvatars,
    private val factory: ProfileGraphFactory,
) : OpenProfiles {

    /** For a test, which makes the objects its own way and needs no Hilt. */
    internal constructor(
        store: ProfileStore,
        sessions: SessionStore,
        roots: ProfileRoots,
        avatars: ProfileAvatars,
        making: GraphMaking,
        formats: ProfileDataFormats,
    ) : this(store, sessions, roots, avatars, ProfileGraphFactory(store, roots, avatars, making, formats))

    /** Where the store is followed, and where what must not hold the follower is handed over. Set by [open]. */
    private lateinit var background: CoroutineScope

    private val mutableCurrent = MutableStateFlow<ProfileGraph?>(null)
    override val current: StateFlow<ProfileGraph?> = mutableCurrent.asStateFlow()

    @Volatile
    override var lastClosed: ProfileGraph? = null
        private set

    private val mutableShown = MutableStateFlow(ShownSession(SessionState.Loading, profileId = null))
    override val shown: StateFlow<ShownSession> = mutableShown.asStateFlow()

    private val opened = AtomicBoolean(false)

    /**
     * The profiles whose folders were handed over to be deleted in this process. Each is handed over once:
     * the store is asked which profiles are being removed at every change it tells, and folders that could
     * not be deleted are left for the next start, not tried — and logged — again at every renewed token.
     */
    private val handedOver = ConcurrentHashMap.newKeySet<String>()

    /**
     * Opens the profiles, blocking: loads the store and builds the current profile's objects before returning,
     * so that a service the system starts in a fresh process finds them. Then follows the store in [scope]:
     * the one collector opens and closes the current profile as it changes, and also sweeps the profiles
     * removed while they sleep, whose folders go at once ([sweep]).
     * Once per process: a second call does nothing, the store is already followed.
     *
     * What an interruption left behind — a profile being removed, a folder that is nobody's — is only listed
     * here, and deleted in the background. Listed as soon as the store is loaded, before any profile's objects
     * are built: the profile may be removed as soon as it is open — a sign-out — and a profile that is open is
     * not one that was left behind, its folders go when it has closed.
     *
     * A profile still being moved in — a migration that did not end — is an account whose data is half in its
     * folders: this start opens nothing, shows that the update could not finish ([halt]) and nothing else,
     * the sign-in screen no more than the rest, and does not follow the store. Its folders are listed, so
     * they stay. The next start tries the migration again.
     */
    fun open(scope: CoroutineScope) {
        if (!opened.compareAndSet(false, true)) return
        background = scope
        var halfMoved = false
        val leftBehind = runBlocking {
            store.load()
            halfMoved = store.beingMoved().isNotEmpty()
            (store.beingRemoved() + nobodys()).also {
                handedOver += it
                if (!halfMoved) follow()
            }
        }
        if (halfMoved) halt() else scope.launch { storeChanges().conflate().collect { followAndSweep() } }
        scope.launch(factory.making.io) { leftBehind.forEach { factory.discard(it) } }
    }

    /**
     * The update that makes the first profile of an older installation could not finish at this start: the
     * account's data is half in its folders, or not there yet. Nothing is opened and the store is not
     * followed — neither the account nor a sign-in screen beside it — and the screens are told, so that they
     * say it rather than show a wait that never ends. Called in place of [open], or after an [open] that
     * failed: what could not be opened is not shown either, and the next start tries again.
     */
    fun halt() {
        mutableShown.value = ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true)
    }

    /**
     * The folders that no profile lists. The folders are listed first and the ids asked after: a profile
     * saved in between is then among the ids, and its folder not among those seen. None when the registry
     * could not be read: every folder may then be a profile's that the file still lists.
     *
     * The accounts' pictures, which the device keeps beside the folders ([ProfileAvatars]), follow the same
     * rule, and those that are nobody's go here, at once: they are a few small files, and no profile is open
     * yet to fetch one meanwhile.
     */
    private suspend fun nobodys(): Set<String> {
        val present = roots.present()
        val listed = store.listedIds() ?: return emptySet()
        avatars.sweep(listed)
        return present - listed
    }

    /**
     * Tells that the store changed, not what to: [follow] reads it. The session alone would not do: who
     * follows is busy while a profile closes, and a sign-out and sign-in that happen meanwhile and end on an
     * equal session — the same account, signed in again — are, for a state flow, no change at all. The id of
     * the current profile is never the same after them.
     *
     * Nor would the two together: a profile removed while it sleeps changes neither, only which profiles are
     * listed. Every removal changes that — only a listed profile can be removed.
     *
     * Whoever collects this conflates it: what was told while a profile closed is one change, the store is
     * read once for all of it.
     */
    private fun storeChanges(): Flow<Unit> = merge(
        sessions.state.map { },
        store.current.map { it?.id }.distinctUntilChanged().map { },
        store.profiles.map { listed -> listed.map { it.id } }.distinctUntilChanged().map { },
    )

    /**
     * What the one collector does at each change: the open profile first, so that a current profile that was
     * removed is closed, and its folders handed over by its closing, before the removed profiles are looked
     * for.
     */
    private suspend fun followAndSweep() {
        follow()
        sweep()
    }

    /**
     * Has the folders of the profiles removed while they slept deleted, in the background, and their records
     * dropped then ([ProfileDisposal.discard]): at once, not at the next start. Nothing of the open profile
     * moves. Folders that cannot be deleted leave their profile as being removed, for the next start, which
     * is logged where they are deleted and never thrown.
     *
     * The open profile is not one of them even when the store says it is being removed: the store has gone
     * further than what is open, and its folders go when it has closed.
     */
    private suspend fun sweep() {
        val open = mutableCurrent.value?.profileId
        store.beingRemoved()
            .filter { it != open && handedOver.add(it) }
            .forEach { background.launch(factory.making.io) { factory.discard(it) } }
    }

    /**
     * Brings what is open in line with the store, as the store is when this runs, whatever change woke it.
     * Never run twice at once: by [open], then by the one collector it starts.
     *
     * The store tells the profile before the session. Read between the two, it shows a profile with the
     * session of before: the profile decides, and the session only says how nobody is signed in.
     *
     * Going from a profile to another shows the wait of a change of profile, never the sign-in screens, and
     * the store is read again once the profile left has closed: closing takes time, and the profile that was
     * current when it began may not be any more. A profile that was current for that time only is never
     * opened; only the last one asked is.
     */
    private suspend fun follow() {
        val open = mutableCurrent.value
        val profile = store.current.value
        val state = sessions.state.value
        when {
            // No profile, and a session that is not yet the signed-out one: told next, followed then.
            profile == null -> if (state is SessionState.SignedOut) {
                mutableShown.value = ShownSession(state, profileId = null)
                open?.let { close(it) }
            }
            // Its tokens or its account changed: the same objects serve it.
            open?.profileId == profile.id -> mutableShown.value = signedIn(profile)
            open == null -> show(profile)
            else -> {
                mutableShown.value = ShownSession(SessionState.Loading, profileId = null, switching = true)
                close(open)
                // Nothing is open any more: this ends on the profile the store has now, or on nobody.
                follow()
            }
        }
    }

    /**
     * The objects exist, and what starts with them has started, before anything is told that the profile is
     * open: neither [current] nor [shown] says so while a starter runs. Nothing is then shown a profile that
     * is half started, and a starter is given what it needs, it does not look for the open profile.
     *
     * A profile whose data is of an older format and could not be started again is not opened: the store has
     * withheld it by then, and has another current profile, or none, which is followed in its place.
     */
    private suspend fun show(profile: Profile) {
        val graph = factory.open(profile) ?: return follow()
        mutableCurrent.value = graph
        // After the new one is published: who finds no open profile always finds the closed one.
        lastClosed = null
        mutableShown.value = signedIn(profile)
    }

    private fun signedIn(profile: Profile) = ShownSession(SessionState.SignedIn(profile.session), profile.id)

    /** Called once the screens were told that it is signed out, or that the profile is being changed. */
    private suspend fun close(graph: ProfileGraph) {
        // Before the open one is withdrawn, for the same reason.
        lastClosed = graph
        mutableCurrent.value = null
        if (factory.close(graph, background)) handedOver += graph.profileId
    }
}
