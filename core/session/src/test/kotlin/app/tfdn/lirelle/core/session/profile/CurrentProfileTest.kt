package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.SessionState
import com.google.common.truth.Truth.assertThat
import dagger.hilt.internal.GeneratedComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrentProfileTest {

    /** What a bridge asks a profile for. */
    private interface NameEntry {
        val name: String
    }

    /** A profile's objects as Hilt makes them: the component is itself every entry point. */
    private class Component(override val name: String) : ProfileComponent, GeneratedComponent, NameEntry

    private class Profiles : OpenProfiles {
        override val current = MutableStateFlow<ProfileGraph?>(null)
        override val shown = MutableStateFlow(ShownSession(SessionState.Loading, profileId = null))
        override var lastClosed: ProfileGraph? = null
    }

    private val profiles = Profiles()
    private val currentProfile = CurrentProfile(profiles)

    private fun graph(id: String) = ProfileGraph(id, Component(id), CoroutineScope(Job()))

    @Test
    fun `the open profile answers`() {
        profiles.current.value = graph("p1")

        assertThat(currentProfile.entry<NameEntry>().name).isEqualTo("p1")
        assertThat(currentProfile.entryOrNull<NameEntry>()?.name).isEqualTo("p1")
    }

    @Test
    fun `before any profile was open a bridge fails, naming what was asked, and a service is told there is none`() {
        val failure = assertThrows(IllegalStateException::class.java) { currentProfile.entry<NameEntry>() }

        assertThat(failure).hasMessageThat().contains("NameEntry")
        assertThat(currentProfile.entryOrNull<NameEntry>()).isNull()
    }

    @Test
    fun `just after a sign-out a bridge is given the closed profile, and a service is told there is none`() {
        profiles.lastClosed = graph("p1")

        assertThat(currentProfile.entry<NameEntry>().name).isEqualTo("p1")
        assertThat(currentProfile.entryOrNull<NameEntry>()).isNull()
    }

    @Test
    fun `the open profile answers rather than the one closed before it`() {
        profiles.lastClosed = graph("p1")
        profiles.current.value = graph("p2")

        assertThat(currentProfile.entry<NameEntry>().name).isEqualTo("p2")
    }

    @Test
    fun `an entry kept by a service is of one profile, and tells when that profile is no longer the open one`() {
        profiles.current.value = graph("p1")
        val kept = checkNotNull(currentProfile.openEntryOrNull<NameEntry>())
        assertThat(kept.entry.name).isEqualTo("p1")
        assertThat(kept.profileId).isEqualTo("p1")
        assertThat(kept.isOpen).isTrue()

        profiles.lastClosed = profiles.current.value
        profiles.current.value = null
        assertThat(kept.isOpen).isFalse()

        // The same account opened again has other objects: the service's are not open again.
        profiles.current.value = graph("p1")
        assertThat(kept.isOpen).isFalse()
        assertThat(currentProfile.openEntryOrNull<NameEntry>()?.isOpen).isTrue()
    }

    @Test
    fun `with no profile open a service is given nothing to keep`() {
        profiles.lastClosed = graph("p1")

        assertThat(currentProfile.openEntryOrNull<NameEntry>()).isNull()
    }
}
