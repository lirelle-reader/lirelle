package app.tfdn.lirelle.core.session.profile

import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope

/**
 * Started once, when a profile's objects are built; what it launches lives in the profile's scope.
 *
 * [start] may be called on any thread — the main thread when the app starts, a background one at a sign-in —
 * and the profile is not open until every starter has returned: it must not block, only launch. It runs
 * before anybody is told that the profile is open, so it uses what it was given, never [CurrentProfile].
 */
fun interface ProfileStarter {
    fun start(scope: CoroutineScope)
}

/**
 * Run before a profile's objects are thrown away: stop what plays, release what holds a file. Given a few
 * seconds: one that has not ended by then is left behind, and the closing goes on.
 */
fun interface ProfileCloser {
    suspend fun close()
}

/** What [ProfileGraphs] asks of a profile's objects as it opens and closes them. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ProfileLifeEntry {
    fun starters(): Set<@JvmSuppressWildcards ProfileStarter>

    fun closers(): Set<@JvmSuppressWildcards ProfileCloser>
}

/** Declared here so that the sets exist even when no module adds to them. */
@Module
@InstallIn(ProfileComponent::class)
internal abstract class ProfileLifeModule {
    @Multibinds
    abstract fun starters(): Set<ProfileStarter>

    @Multibinds
    abstract fun closers(): Set<ProfileCloser>
}
