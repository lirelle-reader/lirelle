package app.tfdn.lirelle.profile

import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.signout.GraphView
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The accessors that give every profile the same object, and why each may: anything else that two open
 * profiles are found to share is a leak from one account to another. Meant to stay short, and exact: an entry
 * that no longer applies fails the test too.
 */
internal val SharedByEveryProfile: Map<String, String> = mapOf(
    "ProfileSourceEntry.sourceAccountPictures" to
        "the BookOrbit source's rules for where an account's picture is: an object that holds nothing",
    "ProfileSourceEntry.getSourceCapabilities" to
        "what BookOrbit can do, the same for every BookOrbit server: an unchanging value that holds nothing",
    "LibraryProfileEntry.librarySourceCapabilities" to
        "what BookOrbit can do, as the view models are given it: the same unchanging value",
)

/** One accessor of one entry point of the profile component: one object a screen or a service may be given. */
internal class Accessor(private val method: Method) {

    /** As it reads in the code: `ReaderCoreProfileEntry.readingPositionStore`. */
    val name: String = "${method.declaringClass.simpleName}.${method.name}"

    /** What it gives for the profile looked at by [view]. */
    fun of(view: GraphView): Any? = try {
        method.invoke(view.graph.component)
    } catch (failure: InvocationTargetException) {
        throw AssertionError("$name could not be made", failure)
    }
}

/**
 * Every accessor of every entry point installed in the profile component, found on the component Hilt
 * generated rather than listed here: an entry point or an accessor added later is covered without anybody
 * thinking of this test.
 */
internal fun profileAccessors(component: ProfileComponent): List<Accessor> =
    interfacesOf(component.javaClass)
        .flatMap { it.declaredMethods.asList() }
        .filter { it.parameterCount == 0 && !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
        .map(::Accessor)
        .sortedBy { it.name }

/** Every interface [type] implements, through its superclasses and their interfaces' own parents. */
private fun interfacesOf(type: Class<*>): Set<Class<*>> {
    val parents = type.interfaces.asList() + listOfNotNull(type.superclass)
    return type.interfaces.toSet() + parents.flatMap { interfacesOf(it) }
}

/** What each accessor gives for the profile looked at by [view], by the accessor's name. */
internal fun List<Accessor>.objectsOf(view: GraphView): Map<String, Any?> = associate { it.name to it.of(view) }

/** The names of the accessors that gave these objects and [other] the very same one. */
internal fun Map<String, Any?>.sharedWith(other: Map<String, Any?>): List<String> =
    filter { (name, made) -> made != null && made === other[name] }.keys.toList()
