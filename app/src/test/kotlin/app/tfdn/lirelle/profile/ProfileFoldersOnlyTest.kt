package app.tfdn.lirelle.profile

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/** What says that a file makes or is an object of a profile. */
private val ProfileMarks = listOf("ProfileComponent::class", "@ProfileScoped")

/**
 * The ways to a folder of the application itself, the same for every profile: a profile's object that takes
 * one keeps its data where the next account finds it, and where removing the profile leaves it.
 */
private val DeviceFolders = Regex(
    "\\b(filesDir|noBackupFilesDir|getExternalFilesDir|cacheDir|externalCacheDir|dataDir|getDir|" +
        "preferencesDataStoreFile|preferencesDataStore|getSharedPreferences|" +
        "getDatabasePath|databaseBuilder|openOrCreateDatabase|StandaloneDatabaseProvider)\\b",
)

/**
 * The files that may do both, by their path from the project's root, and why each may. Empty: a module that
 * has a device half and a profile half is two files.
 */
private val Allowed: Map<String, String> = emptyMap()

/** Where the modules keep what is shipped. */
private val ModuleGroups = listOf("app", "core", "feature", "source")

/** The fewest files the walk must find to have looked at the project at all. */
private const val FewestSources = 200
private const val FewestProfileSources = 30

/** The ways to the application's folders that [source] names, when it makes or is an object of a profile. */
internal fun deviceFoldersOfProfileSource(source: String): Set<String> =
    if (ProfileMarks.none { it in source }) emptySet() else DeviceFolders.findAll(source).map { it.value }.toSet()

/** The project's root: a unit test runs in its module's folder, somewhere under it. */
private fun projectRoot(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

/** The Kotlin sources that are shipped, of every module; what a build left is not looked at. */
private fun mainSources(root: File): List<File> = ModuleGroups.flatMap { group ->
    File(root, group).walkTopDown().onEnter { it.name != "build" }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
}

/**
 * A profile's objects learn where they live from the profile's folders, never from the application: the rule
 * that makes two profiles unable to share a file, kept by reading the sources.
 *
 * It is a scan of text, no more: it looks, in each file that installs into the profile component or holds a
 * profile-scoped class, for the names of the ways to a folder, a preferences file or a database of the whole
 * application. It cannot see a class written in another file and built by such a module, nor a path made some
 * other way: what those do is seen by its effect — two profiles that write, and what each then reads and where
 * its files lie (`ProfileIsolationTest`).
 */
class ProfileFoldersOnlyTest {

    private val root = projectRoot()
    private val sources = mainSources(root)

    @Test
    fun `no source of a profile's objects names a folder of the application`() {
        assertWithMessage("sources found").that(sources.size).isAtLeast(FewestSources)
        val named = sources.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .mapValues { (_, source) -> deviceFoldersOfProfileSource(source) }
            .filter { (path, ways) -> ways.isNotEmpty() && path !in Allowed }

        assertWithMessage("sources of a profile's objects that reach for the application's folders")
            .that(named).isEmpty()
    }

    /** Without this, finding nothing could be looking at nothing, or at no source of a profile. */
    @Test
    fun `the sources of the profiles are among those read`() {
        val ofProfiles = sources.count { file -> file.readText().let { source -> ProfileMarks.any { it in source } } }

        assertThat(ofProfiles).isAtLeast(FewestProfileSources)
    }

    @Test
    fun `a module of the profile component that names the application's folders is caught`() {
        val module = """
            @Module
            @InstallIn(ProfileComponent::class)
            object Leaking {
                @Provides
                fun store(@ApplicationContext context: Context) = Store(File(context.filesDir, "store"))
            }
        """.trimIndent()
        val scoped = "@ProfileScoped\nclass Kept(context: Context) { val file = context.preferencesDataStoreFile(N) }"

        assertThat(deviceFoldersOfProfileSource(module)).containsExactly("filesDir")
        assertThat(deviceFoldersOfProfileSource(scoped)).containsExactly("preferencesDataStoreFile")
    }

    @Test
    fun `the ways to a folder, a database or a store that take no path are caught too`() {
        fun scoped(body: String) = "@ProfileScoped\nclass Kept(context: Context) { $body }"

        assertThat(deviceFoldersOfProfileSource(scoped("val folder = context.getDir(N, 0)")))
            .containsExactly("getDir")
        assertThat(deviceFoldersOfProfileSource(scoped("val base = context.openOrCreateDatabase(N, 0, null)")))
            .containsExactly("openOrCreateDatabase")
        assertThat(deviceFoldersOfProfileSource(scoped("val Context.store by preferencesDataStore(N)")))
            .containsExactly("preferencesDataStore")
        // Media3's own database of the whole application: the index of a cache kept there names every profile's.
        assertThat(deviceFoldersOfProfileSource(scoped("val index = StandaloneDatabaseProvider(context)")))
            .containsExactly("StandaloneDatabaseProvider")
    }

    @Test
    fun `the device's own modules and the profile's own folders are left alone`() {
        val device = "@InstallIn(SingletonComponent::class)\nobject Device { fun f(c: Context) = c.cacheDir }"
        val profile = "@InstallIn(ProfileComponent::class)\nobject Mine { fun f(p: ProfileFolders) = p.cache }"
        val named = "@ProfileScoped\nclass Pages(private val cacheDirectory: File)"

        assertThat(deviceFoldersOfProfileSource(device)).isEmpty()
        assertThat(deviceFoldersOfProfileSource(profile)).isEmpty()
        assertThat(deviceFoldersOfProfileSource(named)).isEmpty()
    }

    /** An entry kept for a file that is gone, or that no longer needs it, would hide the next one. */
    @Test
    fun `every file allowed still needs to be`() {
        val needless = Allowed.keys.filter { path ->
            File(root, path).takeIf { it.isFile }?.let { deviceFoldersOfProfileSource(it.readText()) }.isNullOrEmpty()
        }

        assertThat(needless).isEmpty()
    }
}
