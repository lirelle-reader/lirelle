package app.tfdn.lirelle.feature.reader.bridge

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeReaderEngineTest {

    @Test
    fun `it records what is sent and plays what the page says, even said before anyone listens`() = runTest {
        val engine = FakeReaderEngine(bookBase = "/book/abc/")

        engine.emit(PageMessage.Ready)
        engine.send(HostMessage.Next)
        engine.send(HostMessage.Prev)

        assertThat(engine.sent).containsExactly(HostMessage.Next, HostMessage.Prev).inOrder()
        assertThat(engine.bookBase.value).isEqualTo("/book/abc/")
        engine.messages.test {
            assertThat(awaitItem()).isEqualTo(PageMessage.Ready)
            engine.emit(PageMessage.Tap(0.5, 0.5))
            assertThat(awaitItem()).isEqualTo(PageMessage.Tap(0.5, 0.5))
        }
    }
}
