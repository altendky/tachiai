package net.fstab.tachiai.provider.twitch

import android.content.Context
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore

// One process-wide repository per fixed profile serializes file/key access and guards delayed
// callbacks against Forget. No token retained in singleton memory.
internal object AndroidTwitchAuthorization {
    private val instances = mutableMapOf<TwitchAuthorizationProfile, TwitchSavedAuthorization>()
    private val providerInstances = mutableMapOf<String, TwitchSavedAuthorization>()
    @Synchronized fun forProviderInstance(context: Context, id: String): TwitchSavedAuthorization {
        require(net.fstab.tachiai.presentation.validProviderInstanceId(id))
        if (id == net.fstab.tachiai.presentation.defaultProviderInstanceId(net.fstab.tachiai.presentation.PrototypeService.TWITCH))
            return get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        require(id != net.fstab.tachiai.presentation.defaultProviderInstanceId(net.fstab.tachiai.presentation.PrototypeService.ABEMA))
        return providerInstances.getOrPut(id) {
            TwitchSavedAuthorization(AndroidPrivateSecretStore.twitchProviderInstance(context.applicationContext, id),
                profile = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        }
    }
    @Synchronized fun get(context: Context, profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.TACHIAI): TwitchSavedAuthorization =
        instances.getOrPut(profile) {
            TwitchSavedAuthorization(AndroidPrivateSecretStore(context.applicationContext, profile.storageSlot), profile = profile)
        }
}
