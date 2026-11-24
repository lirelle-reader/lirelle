package app.tfdn.lirelle.core.session.profile

import dagger.hilt.EntryPoints
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * An entry point of the open profile, taken together with the profile it is of. For what keeps a profile's
 * objects longer than the moment of acting — a service: it is built from this one entry, so never from the
 * objects of two profiles, and it lets go of them when [isOpen] no longer holds.
 *
 * @property profileId the profile [entry] is of.
 */
class OpenEntry<T : Any> internal constructor(
    val entry: T,
    private val graph: ProfileGraph,
    private val profiles: OpenProfiles,
) {
    val profileId: String get() = graph.profileId

    /**
     * Whether those objects are still the open profile's. The same account opened again has others: once
     * false, never true again.
     */
    val isOpen: Boolean get() = profiles.current.value === graph

    /** [isOpen], as it changes. */
    val open: Flow<Boolean> = profiles.current.map { it === graph }.distinctUntilChanged()
}

/**
 * The way to the open profile's objects for what Hilt cannot inject them into: the bridges of the view models
 * ([entry]), and the services, the receivers and the activity, which have no bridge and ask for the open
 * profile or nothing ([entryOrNull], [openEntryOrNull]). Asked at the moment of acting; only a service keeps
 * what it is given, with the means to know when its profile closes.
 */
@Singleton
class CurrentProfile @Inject constructor(private val profiles: OpenProfiles) {

    /**
     * The entry point [type] of the open profile, for the bridges of the view models. While none is open, that
     * of the profile closed last ([OpenProfiles.lastClosed]): a screen that is still leaving after a sign-out
     * may make a view model, and must find objects, even ones that do nothing any more. Asking before any
     * profile was ever open is a bug of the caller.
     *
     * The open profile is read first: one being opened is published before the closed one is dropped, so
     * there is always one of the two to find.
     */
    fun <T : Any> entry(type: Class<T>): T {
        val graph = profiles.current.value ?: profiles.lastClosed
            ?: error("No profile was ever open: ${type.simpleName} was asked too early")
        return EntryPoints.get(graph.component, type)
    }

    /**
     * The entry point [type] of the open profile and of no other; null while none is open. For the receivers
     * and services, which the system may start at any time and which act: they must not act on a profile
     * that is closed.
     */
    fun <T : Any> entryOrNull(type: Class<T>): T? = openEntryOrNull(type)?.entry

    /**
     * [entryOrNull], with the means to know when that profile closes: for a service, which keeps what it is
     * given. The profile is read once: the entry and what tells that it closed are of the same one.
     */
    fun <T : Any> openEntryOrNull(type: Class<T>): OpenEntry<T>? =
        profiles.current.value?.let { OpenEntry(EntryPoints.get(it.component, type), it, profiles) }
}

inline fun <reified T : Any> CurrentProfile.openEntryOrNull(): OpenEntry<T>? = openEntryOrNull(T::class.java)

inline fun <reified T : Any> CurrentProfile.entry(): T = entry(T::class.java)

inline fun <reified T : Any> CurrentProfile.entryOrNull(): T? = entryOrNull(T::class.java)
