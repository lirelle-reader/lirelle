package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Real books as the app's own models, for the tests of the modules that know no server: the audiobook manifests
 * (`m4b`: one file; `mp3`: book 48, forty files) and the read-along playlists and spines (`m4b`, `mp3`, `gap`,
 * `unclipped`, `after`). They are written in `fixtures/models/` by the BookOrbit source's `ModelFixturesTest`,
 * from the answers recorded on a server, and checked by it on every run: they are what the source makes of them.
 */
object ModelFixtures {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = " "
    }

    fun manifest(name: String): AudioManifest = read("manifest", name, ManifestFixture.serializer()).toModel()

    fun playlist(name: String): ReadAlongPlaylist = read("playlist", name, PlaylistFixture.serializer()).toModel()

    fun spine(name: String): List<SpineEntry> =
        read("spine", name, ListSerializer(SpineFixture.serializer())).map { it.toModel() }

    /** The file's text for [manifest]: what the fixture `manifest-<name>.json` holds. */
    fun manifestText(manifest: AudioManifest): String =
        json.encodeToString(ManifestFixture.serializer(), ManifestFixture(manifest)) + "\n"

    /** The file's text for [playlist]: what the fixture `playlist-<name>.json` holds. */
    fun playlistText(playlist: ReadAlongPlaylist): String =
        json.encodeToString(PlaylistFixture.serializer(), PlaylistFixture(playlist)) + "\n"

    /** The file's text for [spine]: what the fixture `spine-<name>.json` holds. */
    fun spineText(spine: List<SpineEntry>): String =
        json.encodeToString(ListSerializer(SpineFixture.serializer()), spine.map(::SpineFixture)) + "\n"

    private fun <T> read(kind: String, name: String, serializer: KSerializer<T>): T {
        val path = "fixtures/models/$kind-$name.json"
        val text = checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture $path" }.readText()
        return json.decodeFromString(serializer, text)
    }
}

// Stand-ins of the models, as the files hold them: each made from its model and back.

@Serializable
private data class ManifestFixture(
    val bookId: BookId,
    val contentVersion: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val hasCover: Boolean,
    val assets: List<AssetFixture>,
    val chapters: List<ChapterFixture>,
    val totalDurationMs: Long,
) {
    constructor(model: AudioManifest) : this(
        model.bookId, model.contentVersion, model.title, model.authors, model.narrators, model.hasCover,
        model.assets.map(::AssetFixture), model.chapters.map(::ChapterFixture), model.totalDurationMs,
    )

    fun toModel() = AudioManifest(
        bookId, contentVersion, title, authors, narrators, hasCover,
        assets.map { it.toModel() }, chapters.map { it.toModel() }, totalDurationMs,
    )
}

@Serializable
private data class AssetFixture(
    val assetId: String,
    val fileId: FileId,
    val sequence: Int,
    val format: String,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val etag: String?,
) {
    constructor(model: AudioAsset) : this(
        model.assetId, model.fileId, model.sequence, model.format, model.durationMs, model.sizeBytes, model.etag,
    )

    fun toModel() = AudioAsset(assetId, fileId, sequence, format, durationMs, sizeBytes, etag)
}

@Serializable
private data class ChapterFixture(
    val id: String,
    val title: String,
    val assetId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val assetOffsetMs: Long,
) {
    constructor(model: AudioChapter) : this(
        model.id, model.title, model.assetId, model.sequence, model.startMs, model.endMs, model.assetOffsetMs,
    )

    fun toModel() = AudioChapter(id, title, assetId, sequence, startMs, endMs, assetOffsetMs)
}

@Serializable
private data class PlaylistFixture(
    val bookId: BookId,
    val fileId: FileId,
    val durationMs: Long?,
    val items: List<ItemFixture>,
    val sections: List<SectionFixture>,
    val resources: List<ResourceFixture>,
) {
    constructor(model: ReadAlongPlaylist) : this(
        model.bookId, model.fileId, model.durationMs, model.items.map(::ItemFixture),
        model.sections.map(::SectionFixture), model.resources.map(::ResourceFixture),
    )

    fun toModel() = ReadAlongPlaylist(
        bookId, fileId, durationMs, items.map { it.toModel() }, sections.map { it.toModel() },
        resources.map { it.toModel() },
    )
}

@Serializable
private data class ItemFixture(
    val sectionIndex: Int,
    val textHref: String,
    val fragment: String?,
    val audioHref: String,
    val clipBeginMs: Long,
    val clipEndMs: Long?,
) {
    constructor(model: OverlayItem) : this(
        model.sectionIndex, model.textHref, model.fragment, model.audioHref, model.clipBeginMs, model.clipEndMs,
    )

    fun toModel() = OverlayItem(sectionIndex, textHref, fragment, audioHref, clipBeginMs, clipEndMs)
}

@Serializable
private data class SectionFixture(val index: Int, val href: String, val label: String?) {
    constructor(model: OverlaySection) : this(model.index, model.href, model.label)

    fun toModel() = OverlaySection(index, href, label)
}

@Serializable
private data class ResourceFixture(val href: String, val mediaType: String, val sizeBytes: Long?) {
    constructor(model: OverlayResource) : this(model.href, model.mediaType, model.sizeBytes)

    fun toModel() = OverlayResource(href, mediaType, sizeBytes)
}

@Serializable
private data class SpineFixture(val index: Int, val href: String, val sizeBytes: Long?) {
    constructor(model: SpineEntry) : this(model.index, model.href, model.sizeBytes)

    fun toModel() = SpineEntry(index, href, sizeBytes)
}
