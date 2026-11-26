package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The tabs a car is told have changed: when the profile's sign-in or network turns, when the kept audio changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarRefreshTest {

    private val signIn = MutableStateFlow(false)
    private val online = MutableStateFlow(true)
    private val kept = MutableStateFlow(setOf(BookId("1")))
    private val changed = mutableListOf<CarPlace.Tab>()

    @Test
    fun `nothing is said of what the car was listed at first`() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.refreshCars(signIn, online, kept) { changed += it }

        assertThat(changed).isEmpty()
    }

    @Test
    fun `a sign-in that becomes needed, or is made, changes the tabs the server fills`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.refreshCars(signIn, online, kept) { changed += it }

            signIn.value = true
            assertThat(changed).containsExactly(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)

            changed.clear()
            signIn.value = false
            assertThat(changed).containsExactly(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)
        }

    @Test
    fun `a network that is lost, or returns, changes the tabs the server fills`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.refreshCars(signIn, online, kept) { changed += it }

            online.value = false
            assertThat(changed).containsExactly(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)

            changed.clear()
            online.value = true
            assertThat(changed).containsExactly(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)
        }

    @Test
    fun `a sign-in that becomes needed is said once, though the profile's network goes with it`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.refreshCars(signIn, online, kept) { changed += it }

            // As the profile's network says it: no longer online, a moment after the sign-in is needed.
            signIn.value = true
            online.value = false

            assertThat(changed).containsExactly(CarPlace.Tab.Home, CarPlace.Tab.Audiobooks, CarPlace.Tab.Series)
        }

    @Test
    fun `a book whose audio is kept, or no longer, changes Downloaded alone`() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.refreshCars(signIn, online, kept) { changed += it }

        kept.value = setOf(BookId("1"), BookId("2"))
        kept.value = emptySet()

        assertThat(changed).containsExactly(CarPlace.Tab.Downloaded, CarPlace.Tab.Downloaded)
    }
}
