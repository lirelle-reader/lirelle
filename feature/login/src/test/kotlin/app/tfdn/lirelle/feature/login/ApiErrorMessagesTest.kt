package app.tfdn.lirelle.feature.login

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiErrorMessagesTest {

    @Test
    fun `lock time is rounded up to whole minutes`() {
        assertThat(lockedMinutes(540)).isEqualTo(9)
        assertThat(lockedMinutes(541)).isEqualTo(10)
        assertThat(lockedMinutes(60)).isEqualTo(1)
        assertThat(lockedMinutes(61)).isEqualTo(2)
    }

    @Test
    fun `lock time is never less than one minute`() {
        assertThat(lockedMinutes(0)).isEqualTo(1)
        assertThat(lockedMinutes(1)).isEqualTo(1)
    }
}
