package app.tfdn.lirelle.feature.comics.page

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Notes the thread a picture is worked on once it is decoded: the one that decoded it. */
private class NotingThread : Transformation() {
    var thread: Thread? = null

    override val cacheKey: String = "noting-thread"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        thread = Thread.currentThread()
        return input
    }
}

/** The image loader the tests draw pages with. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageImagesTest {

    @get:Rule
    val images = PageImages()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a page is read and decoded on the thread of the test, before the request returns`() {
        val page = pageImage(folder.root, 0, width = 30, height = 40)
        val noting = NotingThread()
        val request = ImageRequest.Builder(context).data(page.file).transformations(noting).build()

        val result = runBlocking { images.loader.execute(request) }

        assertThat(result).isInstanceOf(SuccessResult::class.java)
        assertThat(noting.thread).isSameInstanceAs(Thread.currentThread())
    }
}
