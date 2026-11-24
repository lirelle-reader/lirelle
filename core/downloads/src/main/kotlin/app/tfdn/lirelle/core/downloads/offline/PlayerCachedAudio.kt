package app.tfdn.lirelle.core.downloads.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.cache.copyWhole
import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import java.io.File
import javax.inject.Inject

/**
 * The player's cache as a source of an audiobook's files: one listened to from end to end before it was
 * downloaded, in the version planned, is copied from there. The cache keeps its own, which it may evict whenever
 * it likes.
 *
 * Only a file planned with the key the player caches it under ([PlannedFile.cacheKey]) can be: a narration
 * track has none, the player caching it by segment.
 */
@OptIn(UnstableApi::class)
class PlayerCachedAudio @Inject constructor(private val cache: AudioCache) : CachedFiles {
    override fun copyOf(file: PlannedFile, into: File): Boolean {
        val key = file.cacheKey?.takeIf { file.kind == FileKind.Audio } ?: return false
        return cache.copyWhole(key, file.expectedBytes, into)
    }
}
