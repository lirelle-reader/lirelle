package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.signout.TestServer
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

internal const val OldUser = 8L
internal const val OldBook = 41L
internal const val OldFile = 7L

/** The id this version names [OldBook] by. */
internal val OldBookId = BookId("$OldBook")

/** The id this version names [OldFile] by. */
internal val OldFileId = FileId("$OldFile")
internal const val OldSeries = 4L
internal const val OldAlbum = 9L
internal const val OldPlace = "epubcfi(/6/4!/4/2)"
internal const val OldNewPlace = "epubcfi(/6/8!/4/2)"
internal const val OldHeardMs = 60_000L
internal const val OldAudioBytes = 300
internal const val OldCover = "$TestServer/api/v1/books/$OldBook/cover"

/** The old preferences files of the account, each with a scratch file left beside it. */
internal val ScratchOwners = listOf("session", "reading", "listening", "downloads")

/** The session the old installation is signed in with: what the profile made of it must hold, tokens too. */
internal val OldSession = bookOrbitSession(
    serverUrl = TestServer,
    sessionId = 42,
    tokens = Tokens(
        accessToken = "access-old",
        accessTokenExpiresAt = Instant.parse("2090-01-01T00:00:00Z"),
        refreshToken = "refresh-old",
        refreshTokenExpiresAt = Instant.parse("2090-06-01T00:00:00Z"),
    ),
    user = UserProfile("$OldUser", "reader", "Ann Reader", email = "ann@example.com"),
)

/** The owner as the old stores wrote it in each of their values. */
internal fun owner(server: String = TestServer, user: Long = OldUser) = """{"serverUrl":"$server","userId":$user}"""

/** What the old stores wrote, by key, for [owner]: the exact text of the version before the profiles. */
internal object OldValues {
    private const val Position = """{"fileId":$OldFile,"cfi":"$OldPlace","percent":0.4,"at":"1970-01-01T00:00:00Z"}"""
    private const val Narration = """{"fileId":$OldFile,"seconds":12.5,"fragment":"f1","sectionIndex":2,""" +
        """"percent":0.3,"at":"1970-01-01T00:00:00Z"}"""
    private const val Bookmark = """{"id":"m1","cfi":"$OldPlace","title":"Chapter 2","origin":"Web",""" +
        """"createdAt":"1970-01-01T00:00:00Z"}"""
    private const val Playback = """{"bookId":$OldBook,"assetId":"a1","positionMs":$OldHeardMs,""" +
        """"bookTimeMs":$OldHeardMs,"at":"1970-01-01T00:00:00Z","operationId":"op-1","baseRevision":0,""" +
        """"manifestRevision":"m1"}"""
    private const val AudioFile = """{"name":"a1","url":"$TestServer/a1","version":"v1",""" +
        """"expectedBytes":$OldAudioBytes,"resumable":true,"kind":"Audio"}"""
    private const val Format = """{"format":"Audiobook","state":{"name":"Complete","receivedBytes":$OldAudioBytes},""" +
        """"files":[$AudioFile],"version":"v1","askedAt":"1970-01-01T00:00:00Z"}"""

    // A bookmark made on the device and not sent yet: shown with the book's marks, and waiting in the queue.
    private const val NewBookmark = """{"id":"m2","cfi":"$OldNewPlace","title":"Chapter 3","origin":"Web",""" +
        """"createdAt":"1970-01-02T00:00:00Z"}"""
    private const val MarkToSend = """{"type":"addBookmark","bookId":$OldBook,"id":"m2","fileId":$OldFile,""" +
        """"cfi":"$OldNewPlace","title":"Chapter 3"}"""

    // What the old writers left out is left out here: they did not write a value that is the default.
    private const val Listened = """{"fileId":$OldFile,"sessionId":"s1","startedAt":"1970-01-01T00:00:00Z",""" +
        """"endedAt":"1970-01-01T00:10:00Z","activeSeconds":600,"endPercent":0.5}"""
    private const val AudioMark = """{"id":"b1","bookId":$OldBook,"positionMs":$OldHeardMs,"title":"Start",""" +
        """"createdAt":"1970-01-01T00:00:00Z"}"""
    private const val AudioMarksToSend = """{"type":"add","bookmark":{"id":"b2","bookId":$OldBook,""" +
        """"positionMs":90000,"title":"Later"}},{"type":"edit","bookId":$OldBook,"id":"b1","title":"Renamed"}"""

    fun reading(owner: String) = mapOf(
        "positions" to """{"owner":$owner,"positions":[$Position]}""",
        "narrations" to """{"owner":$owner,"narrations":[$Narration]}""",
        "outbox" to """{"owner":$owner,"positions":[$Position]}""",
        "marks2" to """{"owner":$owner,"books":[{"bookId":$OldBook,"fileId":$OldFile,""" +
            """"bookmarks":[$Bookmark,$NewBookmark]}],"changes":[$MarkToSend]}""",
        // The first format of the marks: never read, and not to be kept.
        "marks" to """{"s1":{"cfi":"$OldPlace"}}""",
    )

    fun listening(owner: String) = mapOf(
        "playback" to """{"owner":$owner,"positions":[$Playback]}""",
        "playback_outbox" to """{"owner":$owner,"positions":[$Playback]}""",
        "listening_sessions" to """{"owner":$owner,"sessions":[$Listened]}""",
        "audio_bookmarks" to """{"owner":$owner,"books":{"$OldBook":{"bookmarks":[$AudioMark],"version":1}},""" +
            """"changes":[$AudioMarksToSend]}""",
    )

    fun downloads(owner: String) = mapOf(
        "downloads" to """{"owner":$owner,"books":[{"bookId":$OldBook,"formats":[$Format],""" +
            """"downloadedAt":"1970-01-01T00:00:00Z"}]}""",
        "update_check" to """{"owner":$owner,"atMillis":0}""",
    )

    val library = mapOf(
        "view.books" to """{"presentation":"List","sort":"title","descending":true}""",
        "recent_searches" to """["mars","dune"]""",
    )

    val comics = mapOf(
        "fit" to "Width",
        "twoPages" to "Always",
        "background" to "White",
        "direction.series.$OldSeries" to "RightToLeft",
        "direction.book.$OldAlbum" to "RightToLeft",
    )

    /** The device's settings alone, as the split must leave them. */
    const val PlayerDevice =
        """{"backSeconds":45,"forwardSeconds":10,"rewindOnResume":false,"shakeToExtend":false,"lastSpeed":1.5}"""
    const val Speeds = """"speeds":{"$OldBook":1.5},"narrationSpeeds":{"$OldBook":1.25}"""
    val player = mapOf("player_settings" to PlayerDevice.dropLast(1) + "," + Speeds + "}")

    /**
     * The files that are the device's alone, by their name, with nothing in them at its default: the
     * migration has nothing to do with them, and must leave them exactly so.
     */
    val untouched = mapOf(
        "reader_settings" to mapOf(
            "settings" to """{"theme":"Sepia","font":"Serif","fontSizeStep":5,"lineHeightStep":3,""" +
                """"margins":"Wide","align":"Justify","hyphenate":false,"flow":"Scrolled","volumeKeys":true,""" +
                """"brightnessEdge":"Right","brightness":0.4,"headerLeft":"BookTitle","headerRight":"Nothing",""" +
                """"footerLeft":"Nothing","footerRight":"BookPercent","footnotePanel":false,""" +
                """"cornerBookmark":false,"lastHighlightColour":"#22C55E"}""",
        ),
        "download_settings" to mapOf(
            "download_settings" to """{"allowMetered":true,"notificationsAsked":true,"seriesFormats":["Audiobook"]}""",
        ),
        "appearance" to mapOf("theme" to "Dark"),
    )
}

/** Writes the preferences file [name] of [context] by its raw keys, then lets go of it. */
internal suspend fun writePreferences(context: Context, name: String, values: Map<String, String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        .edit { stored -> values.forEach { (key, value) -> stored[stringPreferencesKey(key)] = value } }
    scope.coroutineContext.job.cancelAndJoin()
}

/**
 * An installation of the version before the profiles, written as that version left it: at the root of the
 * application's folders, by raw keys and the old stores' own JSON. Never through today's stores, which no
 * longer write there.
 *
 * @param cipher what encrypted the tokens of the old session: the one the application decrypts with.
 */
internal class OldInstallation(val context: Context, private val cipher: TokenCipher) {

    /**
     * @param signedIn false for an installation that was signed out, which remembers its server only.
     * @param owner whose the account's stores and the last book say they are.
     * @param email the address of the account; null for an account that has none, which the old writer left
     *   out of the file.
     * @param untouched false to leave out the files that are the device's alone ([OldValues.untouched]): for
     *   a test that writes under an application already started, which holds one of them open.
     */
    suspend fun write(
        signedIn: Boolean = true,
        owner: String = owner(),
        lastBookUser: Long = OldUser,
        email: String? = OldSession.user.email,
        untouched: Boolean = true,
    ) {
        writePreferences(context, "session", session(signedIn, email))
        writePreferences(context, "reading", OldValues.reading(owner))
        writePreferences(context, "listening", OldValues.listening(owner))
        writePreferences(context, "downloads", OldValues.downloads(owner))
        writeDeviceFiles()
        if (untouched) OldValues.untouched.forEach { (name, values) -> writePreferences(context, name, values) }
        writeFiles()
        context.getSharedPreferences("player_last_book", Context.MODE_PRIVATE).edit()
            .putString("server", TestServer)
            .putLong("user", lastBookUser)
            .putLong("book", OldBook)
            .putString("title", "Mars")
            .putString("cover", OldCover)
            .commit()
    }

    /** The three files that mixed the device's settings and the account's. */
    suspend fun writeDeviceFiles() {
        writePreferences(context, "library", OldValues.library)
        writePreferences(context, "comics_settings", OldValues.comics)
        writePreferences(context, "player_settings", OldValues.player)
    }

    /**
     * The old file, as its writer made it: its JSON did not encode a value that is the default, so an account
     * without an address has no `email`, and one that may download — every account of these tests — has no
     * `canDownload`.
     */
    private fun session(signedIn: Boolean, email: String?): Map<String, String> {
        val tokens = """{"accessToken":"access-old","accessTokenExpiresAt":"2090-01-01T00:00:00Z",""" +
            """"refreshToken":"refresh-old","refreshTokenExpiresAt":"2090-06-01T00:00:00Z"}"""
        val address = email?.let { ""","email":"$it"""" }.orEmpty()
        val stored = """{"serverUrl":"$TestServer","sessionId":42,"user":{"id":$OldUser,"username":"reader",""" +
            """"name":"Ann Reader"$address},"encryptedTokens":"${cipher.encrypt(tokens)}"}"""
        val server = mapOf("server_url" to TestServer)
        return if (signedIn) server + ("session" to stored) else server
    }

    private fun writeFiles() {
        fun file(root: File, path: String) = File(root, path).apply { parentFile?.mkdirs() }
        file(context.filesDir, "downloads/$OldBook/audiobook/a1").writeBytes(ByteArray(OldAudioBytes))
        file(context.filesDir, "downloads/$OldBook/detail.json").writeText("{}")
        file(context.filesDir, "readalong/$OldFile-none.map.gz").writeText("map")
        file(context.cacheDir, "covers/cover").writeText("cover")
        file(context.cacheDir, "reader/book/chapter").writeText("chapter")
        file(context.cacheDir, "comics/31-v1/0").writeText("page")
        file(context.cacheDir, "audio/0/stream.exo").writeText("audio")
        // What a write that was cut short leaves beside a preferences file.
        ScratchOwners.forEach { file(context.filesDir, "datastore/$it.preferences_pb.tmp").writeText("scratch") }
        context.openOrCreateDatabase("exoplayer_internal.db", Context.MODE_PRIVATE, null).close()
    }
}
