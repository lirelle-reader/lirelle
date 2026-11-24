package app.tfdn.lirelle.feature.login

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The server address is typed as an address: no correction, no capital letter, the address keyboard. */
class AddressKeyboardTest {

    @Test
    fun `the address field asks for the address keyboard without correction nor capitals`() {
        assertThat(AddressKeyboard.keyboardType).isEqualTo(KeyboardType.Uri)
        assertThat(AddressKeyboard.autoCorrectEnabled).isFalse()
        assertThat(AddressKeyboard.capitalization).isEqualTo(KeyboardCapitalization.None)
        assertThat(AddressKeyboard.imeAction).isEqualTo(ImeAction.Go)
    }
}
