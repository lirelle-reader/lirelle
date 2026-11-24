package app.tfdn.lirelle.core.downloads.di

import com.google.common.truth.Truth.assertThat
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Test

/** The downloads' client: the authenticated one, on connections of its own. */
class DownloadClientTest {

    /** Stands for what the authenticated client adds to every request: the token. */
    private val marker = Interceptor { it.proceed(it.request()) }

    /** Stands for the authenticated client's token refresh. */
    private val refresh = Authenticator { _, _ -> null }

    /** Stands for the long-transfer interceptor the profile's source binds. */
    private val longTransfer = Interceptor { it.proceed(it.request()) }
    private val authenticated = OkHttpClient.Builder().addInterceptor(marker).authenticator(refresh).build()
    private val download = DownloadsEngineModule.downloadClient(authenticated, longTransfer)

    @Test
    fun `the downloads do not share the pages' connections`() {
        assertThat(download.connectionPool).isNotSameInstanceAs(authenticated.connectionPool)
    }

    @Test
    fun `each download client built gets a pool of its own, not one pool for them all`() {
        val other = DownloadsEngineModule.downloadClient(authenticated, longTransfer)

        assertThat(download.connectionPool).isNotSameInstanceAs(other.connectionPool)
    }

    @Test
    fun `the downloads keep the authenticated client's interceptors and token refresh, and wait for a file`() {
        assertThat(download.interceptors).contains(marker)
        assertThat(download.authenticator).isSameInstanceAs(refresh)
        assertThat(download.interceptors).contains(longTransfer)
    }
}
