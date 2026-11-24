package app.tfdn.lirelle.feature.settings.appearance

import android.app.Application
import android.app.UiModeManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NightModeTest {

    private val manager: UiModeManager =
        ApplicationProvider.getApplicationContext<Application>().getSystemService(UiModeManager::class.java)
    private val nightMode = SystemNightMode(manager)

    private fun applied(): Int = shadowOf(manager).applicationNightMode

    @Test
    fun `the dark theme makes the app dark for the system`() {
        nightMode.apply(AppTheme.Dark)

        assertThat(applied()).isEqualTo(UiModeManager.MODE_NIGHT_YES)
    }

    @Test
    fun `the light theme makes the app light for the system`() {
        nightMode.apply(AppTheme.Light)

        assertThat(applied()).isEqualTo(UiModeManager.MODE_NIGHT_NO)
    }

    @Test
    fun `the system theme gives the choice back to the system`() {
        nightMode.apply(AppTheme.Dark)

        nightMode.apply(AppTheme.System)

        assertThat(applied()).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
    }
}
