package app.tfdn.lirelle.feature.player.di

import com.google.common.truth.Truth.assertThat
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Test

/** The audio's client: the authenticated one, on connections of its own. */
class MediaClientTest {

    /** Stands for what the authenticated client adds to every request: the token. */
    private val marker = Interceptor { it.proceed(it.request()) }

    /** Stands for the authenticated client's token refresh. */
    private val refresh = Authenticator { _, _ -> null }

    /** Stands for the long-transfer interceptor the profile's source binds. */
    private val longTransfer = Interceptor { it.proceed(it.request()) }
    private val authenticated = OkHttpClient.Builder().addInterceptor(marker).authenticator(refresh).build()
    private val media = PlayerServiceModule.mediaClient(authenticated, longTransfer)

    @Test
    fun `the audio does not share the pages' connections`() {
        assertThat(media.connectionPool).isNotSameInstanceAs(authenticated.connectionPool)
    }

    @Test
    fun `each audio client built gets a pool of its own, not one pool for them all`() {
        val other = PlayerServiceModule.mediaClient(authenticated, longTransfer)

        assertThat(media.connectionPool).isNotSameInstanceAs(other.connectionPool)
    }

    @Test
    fun `the audio keeps the authenticated client's interceptors and token refresh, and waits for a file`() {
        assertThat(media.interceptors).contains(marker)
        assertThat(media.authenticator).isSameInstanceAs(refresh)
        assertThat(media.interceptors).contains(longTransfer)
    }
}
