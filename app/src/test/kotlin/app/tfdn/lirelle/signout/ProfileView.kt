package app.tfdn.lirelle.signout

import app.tfdn.lirelle.core.session.profile.ProfileGraph
import app.tfdn.lirelle.di.ProfileDataOnDevice
import dagger.hilt.EntryPoints
import java.io.File

/**
 * A profile as a test looks at it: its objects, and what its folders hold. What an account leaves ([Traces])
 * is written and read through one of these, so that the same traces serve the profile the app shows and one
 * opened beside it.
 */
internal interface ProfileView {
    /** The entry point [type] of the profile looked at. */
    fun <T : Any> entry(type: Class<T>): T

    /** What the folders looked at hold at their top: the folder or the file of each store. */
    fun stores(): List<File>

    /** The files kept in the folder [store], under the files or under the cache. */
    fun filesIn(store: String): List<File> =
        stores().filter { it.name == store }.flatMap { folder -> folder.walkTopDown().filter { it.isFile } }
}

internal inline fun <reified T : Any> ProfileView.profile(): T = entry(T::class.java)

/**
 * The objects of one profile, whether or not it is the one the app shows, and its own folders alone: what
 * another profile keeps beside it is not seen here.
 */
internal class GraphView(val graph: ProfileGraph) : ProfileView {

    val id: String get() = graph.profileId

    val folders get() = profile<ProfileDataOnDevice>().profileFolders()

    override fun <T : Any> entry(type: Class<T>): T = EntryPoints.get(graph.component, type)

    override fun stores(): List<File> =
        folders.let { listOf(it.files, it.cache) }.flatMap { it.listFiles().orEmpty().asList() }
}
