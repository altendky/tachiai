package net.fstab.tachiai.provider.twitch

import android.content.Context
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore

// One process-wide repository per fixed profile serializes file/key access and guards delayed
// callbacks against Forget. No token retained in singleton memory.
internal object AndroidTwitchAuthorization {
    private val instances = mutableMapOf<TwitchAuthorizationProfile, TwitchSavedAuthorization>()
    @Synchronized fun get(context: Context, profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.TACHIAI): TwitchSavedAuthorization =
        instances.getOrPut(profile) {
            TwitchSavedAuthorization(AndroidPrivateSecretStore(context.applicationContext, profile.storageSlot), profile = profile)
        }
}
