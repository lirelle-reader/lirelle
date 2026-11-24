package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.ServerKind
import dagger.BindsInstance
import dagger.hilt.DefineComponent
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Scope
import kotlinx.coroutines.CoroutineScope

/** One instance per profile: built when the profile opens, gone with it. */
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class ProfileScoped

/** The scope that lives as long as the profile's objects, and is cancelled when they are thrown away. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProfileLife

/**
 * What the source of the kind of server [kind] binds in the profile component: its implementation of a contract
 * of the core modules, beside the others'. The contract itself, unqualified, is the one of the profile's own kind,
 * chosen on [ProfileSession.kind] when the profile opens; only the chosen source's objects are made.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SourceOf(val kind: ServerKind)

/**
 * Everything that belongs to one profile: its network client, repositories, stores, caches, downloads, player.
 * Nothing of it is reachable from the singleton component, so nothing that outlives a profile can hold it.
 */
@ProfileScoped
@DefineComponent(parent = SingletonComponent::class)
interface ProfileComponent

/** What a profile's objects are built with: its own session, its own folders, and the scope they live in. */
@DefineComponent.Builder
interface ProfileComponentBuilder {
    fun session(@BindsInstance session: ProfileSession): ProfileComponentBuilder

    fun folders(@BindsInstance folders: ProfileFolders): ProfileComponentBuilder

    fun scope(@BindsInstance @ProfileLife scope: CoroutineScope): ProfileComponentBuilder

    fun build(): ProfileComponent
}
