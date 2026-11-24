package app.tfdn.lirelle.core.network.di

import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import okhttp3.OkHttpClient

/**
 * The client that speaks to a server as one account, carrying its credential as the profile's source says
 * ([SessionAuthorizer]). One per profile, over the profile's own session, so that no request made for one profile
 * carries, or renews, the credential of another.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object NetworkProfileModule {

    @Provides
    @ProfileScoped
    @AuthenticatedClient
    fun authenticatedClient(@PublicClient client: OkHttpClient, authorizer: SessionAuthorizer): OkHttpClient =
        newAuthenticatedClient(client, authorizer)
}

/** The network objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface NetworkProfileEntry {
    fun networkAccountRepository(): AccountRepository
}

/**
 * Gives view models the account of the open profile. Unscoped, and in no component that outlives a profile:
 * each view model gets that of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object NetworkBridges {

    @Provides
    fun accountRepository(current: CurrentProfile): AccountRepository =
        current.entry<NetworkProfileEntry>().networkAccountRepository()
}
