package app.tfdn.lirelle.core.session.profile

import android.util.Log
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileDataFormats
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.kindAndFrames
import dagger.hilt.EntryPoints
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** The tag of what is told to logcat here. */
private const val Tag = "Profiles"

/**
 * Makes the objects of a profile and throws them away, whichever profile it is: it knows nothing of which one
 * is current. In the application only [ProfileGraphs] uses it, for the current profile, one at a time: a
 * profile that is not current sleeps, and has no objects. It is apart so that the objects of a second profile
 * can be made beside those of the first, which only the tests of what two profiles share do.
 *
 * Nothing here keeps what it made: whoever opens a profile closes it, and never opens one that is still open —
 * two sets of objects over the same folders would both hold its files.
 */
@Singleton
class ProfileGraphFactory internal constructor(
    private val store: ProfileStore,
    private val roots: ProfileRoots,
    avatars: ProfileAvatars,
    internal val making: GraphMaking,
    formats: ProfileDataFormats,
) {

    @Inject
    constructor(
        store: ProfileStore,
        formats: ProfileDataFormats,
        roots: ProfileRoots,
        avatars: ProfileAvatars,
        builder: Provider<ProfileComponentBuilder>,
    ) : this(
        store,
        roots,
        avatars,
        GraphMaking(
            build = { session, folders, scope ->
                builder.get().session(session).folders(folders).scope(scope).build()
            },
            lifeOf = { EntryPoints.get(it, ProfileLifeEntry::class.java) },
            // The kind of the failure and where it came from, never what it says: a closer's failure may
            // quote the profile's folder or its server.
            warn = { message, failure ->
                Log.w(Tag, failure?.let { "$message: ${it.kindAndFrames()}" } ?: message)
            },
        ),
        formats,
    )

    private val disposal = ProfileDisposal(store, roots, avatars, making)
    private val reset = ProfileDataReset(roots, formats, making)

    /**
     * The objects of [profile], in its own folders, with what starts with them started. May be called on any
     * thread, and returns once every starter has: nobody is handed a profile that is half started.
     *
     * The folders of a profile whose data is of an older format are emptied first ([ProfileDataReset]): no
     * object of a profile is ever built over data of an older format.
     *
     * @return null when they could not be: the profile is then withheld from this start, and nothing of it was
     *   built — the registry has another current profile, or none.
     */
    suspend fun open(profile: Profile): ProfileGraph? {
        if (!reset.readied(profile)) return null
        val scope = CoroutineScope(SupervisorJob() + making.graph)
        val folders = roots.of(profile.id).also {
            it.files.mkdirs()
            it.cache.mkdirs()
        }
        val component = making.build(StoreProfileSession(profile, store, scope), folders, scope)
        making.lifeOf(component).starters().forEach { it.start(scope) }
        return ProfileGraph(profile.id, component, scope)
    }

    /**
     * Throws the objects of [graph] away ([ProfileDisposal.dispose]): once this returns they hold no file, and
     * the same profile can be opened again. The folders of a profile that was removed are then deleted in
     * [background], which is not waited for.
     *
     * @return whether the profile was removed, and its folders are being deleted.
     */
    suspend fun close(graph: ProfileGraph, background: CoroutineScope): Boolean = disposal.dispose(graph, background)

    /** Deletes what a removed profile kept on the device ([ProfileDisposal.discard]). */
    internal suspend fun discard(id: String) = disposal.discard(id)
}
