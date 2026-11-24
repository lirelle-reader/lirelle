package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * An engine without a page: it records what the host sends, and a test plays the page's part with [emit].
 * Like the real one, it keeps messages until they are collected and has one collector.
 */
class FakeReaderEngine(bookBase: String? = null) : ReaderEngine {
    private val incoming = Channel<PageMessage>(Channel.UNLIMITED)

    override val messages: Flow<PageMessage> = incoming.receiveAsFlow()

    override val bookBase = MutableStateFlow(bookBase)

    /** Everything sent to the page, oldest first. */
    val sent = mutableListOf<HostMessage>()

    override fun send(message: HostMessage) {
        sent += message
    }

    /** The page says something. */
    fun emit(message: PageMessage) {
        incoming.trySend(message)
    }
}
