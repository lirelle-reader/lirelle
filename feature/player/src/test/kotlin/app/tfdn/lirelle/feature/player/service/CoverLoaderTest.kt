package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

private const val WaitSeconds = 10L

/** The cover of the notification and of the system's media controls: from the device, or from the server. */
@RunWith(AndroidJUnit4::class)
class CoverLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val image = ByteArrayOutputStream().also {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** The addresses asked of the server through [client]. */
    private val asked = mutableListOf<String>()
    private val client = recording(asked)

    /** A client that writes down each address asked [into], and answers it with the image. */
    private fun recording(into: MutableList<String>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                into += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(image.toResponseBody("image/png".toMediaType()))
                    .build()
            },
        )
        .build()

    private fun load(uri: Uri, loader: BitmapLoader = coverLoader(context, client)): Bitmap {
        val loading = loader.loadBitmap(uri)
        shadowOf(Looper.getMainLooper()).idle()
        return loading.get(WaitSeconds, TimeUnit.SECONDS)
    }

    @Test
    fun `the cover kept with a download is read from the device, and nothing is asked of the server`() {
        // As the downloads keep it: a file without an extension.
        val cover = File.createTempFile("cover", null).apply {
            deleteOnExit()
            writeBytes(image)
        }

        assertThat(load(Uri.fromFile(cover))).isNotNull()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `the cover of a book that is not downloaded is asked of the server, as before`() {
        assertThat(load(Uri.parse("https://books.example/c/42"))).isNotNull()
        assertThat(asked).containsExactly("https://books.example/c/42")
    }

    @Test
    @OptIn(UnstableApi::class)
    fun `the session's covers are asked with the pages' client, never behind the audio on its own connections`() {
        val audioAsked = mutableListOf<String>()
        val streams = MediaStreams(
            cache = AudioCache(context, File(context.cacheDir, "audio")),
            client = recording(audioAsked),
            coverClient = client,
            downloaded = DownloadedFiles.None,
        )

        assertThat(load(Uri.parse("https://books.example/c/42"), streams.sessionCovers(context))).isNotNull()
        assertThat(asked).containsExactly("https://books.example/c/42")
        assertThat(audioAsked).isEmpty()
    }
}
