package app.tfdn.lirelle.feature.settings.about

import app.cash.turbine.test
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LicencesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val list = Licences(
        bundled = listOf(Component("bundled:engine", "engine", "Ann Engineer", bundled = true, licences = emptyList())),
        libraries = emptyList(),
    )

    @Test
    fun `there is no list while it is read, then the one the source gave`() = runTest {
        val read = CompletableDeferred<Licences>()
        val viewModel = LicencesViewModel { read.await() }

        viewModel.licences.test {
            assertThat(awaitItem()).isNull()

            read.complete(list)

            assertThat(awaitItem()).isEqualTo(list)
        }
    }

    @Test
    fun `a name made like coordinates may break between its parts, and no other name changes`() {
        val gap = "\u200B"

        assertThat("androidx.core:core-viewtree".withBreakPlaces())
            .isEqualTo("androidx.${gap}core:${gap}core-${gap}viewtree")
        assertThat("Guava: Google Core Libraries for Java".withBreakPlaces())
            .isEqualTo("Guava: Google Core Libraries for Java")
        assertThat("Google, Inc.".withBreakPlaces()).isEqualTo("Google, Inc.")
    }

    @Test
    fun `the list is read once, whoever watches it`() = runTest {
        var reads = 0
        val viewModel = LicencesViewModel {
            reads++
            list
        }

        viewModel.licences.test { assertThat(expectMostRecentItem()).isEqualTo(list) }
        viewModel.licences.test { assertThat(expectMostRecentItem()).isEqualTo(list) }

        assertThat(reads).isEqualTo(1)
    }
}
