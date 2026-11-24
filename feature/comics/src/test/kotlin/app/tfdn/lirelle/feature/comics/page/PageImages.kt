package app.tfdn.lirelle.feature.comics.page

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ui.LocalLirelleImageLoader
import coil3.EventListener
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import org.junit.rules.ExternalResource

/**
 * The image loader of a test that draws pages: it reads and decodes each picture on the thread that asks for it,
 * the test's, and is shut down when the test ends. Without it Coil's own loader would be used, which decodes on
 * threads of its own that no test waits for: the platform's graphics could be called after the test returned.
 *
 * Make it the outermost rule (`order = 0`, the Compose rule after it): it must outlive what is drawn.
 */
class PageImages : ExternalResource() {
    private var made: ImageLoader? = null

    val loader: ImageLoader get() = checkNotNull(made) { "The rule has not started" }

    /** Every picture asked of the loader, in order, as the loader was about to work on it. */
    val requests = mutableListOf<ImageRequest>()

    /** As the app's loader: pictures fade in, unless the request says otherwise. */
    override fun before() {
        val context: Context = ApplicationProvider.getApplicationContext()
        made = ImageLoader.Builder(context)
            .interceptorCoroutineContext(Dispatchers.Main.immediate)
            .fetcherCoroutineContext(Dispatchers.Main.immediate)
            .decoderCoroutineContext(Dispatchers.Main.immediate)
            .crossfade(true)
            .eventListener(
                object : EventListener() {
                    override fun onStart(request: ImageRequest) {
                        requests += request
                    }
                },
            )
            .build()
    }

    override fun after() {
        made?.shutdown()
        made = null
    }

    /** What is drawn inside takes its pages from this loader, as the app's screens take theirs from the app's. */
    @Composable
    fun Provide(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalLirelleImageLoader provides loader, content = content)
}
