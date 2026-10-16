package net.fstab.tachiai.presentation

internal data class ProviderDisplayNames(val primary: String, val provider: String? = null) {
    val inline get() = listOfNotNull(primary, provider).joinToString(" · ")
}

internal fun providerDisplayNames(name: String, provider: String): ProviderDisplayNames = when {
    name.isBlank() -> ProviderDisplayNames(provider)
    name.contains(provider, ignoreCase = true) -> ProviderDisplayNames(name)
    else -> ProviderDisplayNames(name, provider)
}

internal fun providerDisplayNames(instance: ProviderInstance): ProviderDisplayNames =
    providerDisplayNames(instance.name, instance.service.title)
