package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.WrittenPlace
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.audio.settings.toValidSpeed
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryPlaybackStore : PlaybackStore {
    val positions = mutableMapOf<BookId, LocalPlayback>()
    val versions = mutableMapOf<BookId, String>()
    val written = mutableMapOf<BookId, Instant>()

    override suspend fun get(bookId: BookId) = positions[bookId]

    override suspend fun put(position: LocalPlayback) {
        positions[position.bookId] = position
    }

    override suspend fun knownVersion(bookId: BookId) = versions[bookId]

    override suspend fun setKnownVersion(bookId: BookId, version: String) {
        versions[bookId] = version
    }

    override suspend fun lastWritten(bookId: BookId): Instant? = written[bookId]

    override suspend fun setLastWritten(bookId: BookId, at: Instant, place: WrittenPlace?) {
        if (written[bookId]?.isBefore(at) != false) written[bookId] = at
        if (place != null) places[bookId] = (places[bookId].orEmpty() - place + place).takeLast(8)
    }

    private val places = mutableMapOf<BookId, List<WrittenPlace>>()

    override suspend fun writtenPlaces(bookId: BookId): List<WrittenPlace> = places[bookId].orEmpty()
}

class InMemoryPlayerSettingsStore(initial: PlayerSettings = PlayerSettings()) : PlayerSettingsStore {
    override val settings = MutableStateFlow(initial)
    val speeds = mutableMapOf<BookId, Float>()
    val narrationSpeeds = mutableMapOf<BookId, Float>()

    override suspend fun update(t: (PlayerSettings) -> PlayerSettings) {
        settings.value = t(settings.value).normalized()
    }

    override suspend fun speedOf(bookId: BookId) = speeds[bookId]

    override suspend fun setSpeed(bookId: BookId, speed: Float) {
        speeds[bookId] = speed.toValidSpeed()
        settings.value = settings.value.copy(lastSpeed = speed.toValidSpeed())
    }

    override suspend fun narrationSpeedOf(bookId: BookId) = narrationSpeeds[bookId]

    override suspend fun setNarrationSpeed(bookId: BookId, speed: Float) {
        narrationSpeeds[bookId] = speed.toValidSpeed()
    }
}

class InMemoryNarrationStore : NarrationStore {
    val narrations = mutableMapOf<FileId, LocalNarration>()

    override suspend fun get(fileId: FileId) = narrations[fileId]

    override suspend fun put(n: LocalNarration) {
        narrations[n.fileId] = n
    }
}
