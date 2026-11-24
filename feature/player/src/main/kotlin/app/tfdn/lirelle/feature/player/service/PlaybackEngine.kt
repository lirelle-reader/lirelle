package app.tfdn.lirelle.feature.player.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.tfdn.lirelle.core.audio.cache.BufferPolicy
import okhttp3.OkHttpClient

/**
 * The engine: the book's files from the device when they are downloaded, through the audio cache otherwise,
 * about 10 minutes buffered ahead (30 unmetered), the audio focus of spoken audio (calls and navigation prompts
 * pause it; it resumes after a short interruption), a pause when headphones are unplugged, and the CPU and Wi-Fi
 * kept awake while it plays.
 */
@OptIn(UnstableApi::class)
fun newPlaybackEngine(context: Context, streams: MediaStreams, isUnmetered: () -> Boolean): ExoPlayer {
    val speech = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(streams.cache.dataSourceFactory(streams.client, streams.downloaded)),
        )
        .setLoadControl(BufferPolicy.loadControl(isUnmetered))
        .setAudioAttributes(speech, true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
}

/**
 * The cover of the notification and of the system's media controls, decoded here and handed over as an image:
 * the file kept with a download (a `file` address) is read from the device, any other address is asked of the
 * server through the authenticated client.
 */
@OptIn(UnstableApi::class)
fun coverLoader(context: Context, client: OkHttpClient): BitmapLoader = DataSourceBitmapLoader.Builder(context)
    .setDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client)))
    .build()

/**
 * The session's covers: with the pages' client, not the audio's, whose connections the read-ahead fills and
 * behind which a cover would wait.
 */
@OptIn(UnstableApi::class)
fun MediaStreams.sessionCovers(context: Context): BitmapLoader = coverLoader(context, coverClient)
