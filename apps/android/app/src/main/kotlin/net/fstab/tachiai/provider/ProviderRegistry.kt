package net.fstab.tachiai.provider

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.twitch.TwitchAdapter

object ProviderRegistry {
    private val adapters = listOf(AbemaAdapter, TwitchAdapter).associateBy(ProviderAdapter::id)

    fun require(providerId: ProviderId): ProviderAdapter =
        requireNotNull(adapters[providerId]) { "No provider adapter for ${providerId.value}." }
}
