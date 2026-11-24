package app.tfdn.lirelle.feature.reader.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The reader page as the view model sees it. */
interface ReaderEngine {
    /**
     * What the page says, validated. Messages are kept until they are collected, so a `ready` sent before anyone
     * listens is not lost; there is one collector, and each message is delivered once.
     */
    val messages: Flow<PageMessage>

    /**
     * Where the book given to the web view is served (`/book/<token>/`), for [HostMessage.Open.base]; null while
     * there is none. The token is new each time a book is given, and the address of the book before stops working.
     */
    val bookBase: StateFlow<String?>

    /** To be called on the main thread, and only once the page has said it is ready. */
    fun send(message: HostMessage)
}
