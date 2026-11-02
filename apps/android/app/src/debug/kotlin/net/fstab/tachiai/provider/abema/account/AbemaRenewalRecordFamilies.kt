package net.fstab.tachiai.provider.abema.account

import java.security.MessageDigest
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.configuredProviderInstanceBindingName
import net.fstab.tachiai.platform.storage.twitchCatalogInstanceBindingName
import net.fstab.tachiai.platform.storage.twitchProviderInstanceBindingName
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.PrototypeService

internal enum class AbemaRenewalRecordFamily {
    ROUTE_PROFILES, LEGACY_SETUP, PROVIDER_REGISTRY, QUALITY, CONFIGURED_SOURCE, ABEMA_ACCOUNT,
    TWITCH_AUTHORIZATION, TWITCH_FORGET_PENDING, COORDINATION_LOCK, DIAGNOSTIC_JOURNAL, CDN_REVIEW_JOURNAL,
    RECOVERY_CHECKPOINT, SELECTED_ACCOUNT_AUXILIARY, ACCOUNT_AUXILIARY, PRIVATE_RECORD_AUXILIARY,
    SHARED_PREFERENCES, UNKNOWN,
}

internal data class AbemaRenewalFamilyMutation(val family: AbemaRenewalRecordFamily, val changed: Int, val added: Int, val removed: Int)

private fun dynamic(name: String, prefix: String, suffix: String, binding: (String) -> String): Boolean {
    if (!name.startsWith(prefix) || !name.endsWith(suffix)) return false
    val id = name.removePrefix(prefix).removeSuffix(suffix)
    return validProviderInstanceId(id) && runCatching { binding(id) + suffix == name }.getOrDefault(false)
}

private fun account(name: String, suffix: String = ".enc") = dynamic(name, "abema-account-", suffix) { "abema-account-$it" }

private fun encryptedFamily(name: String): AbemaRenewalRecordFamily? {
    PrivateAuthorizationSlot.entries.singleOrNull { it.bindingName + ".enc" == name }?.let { slot ->
        return when (slot) {
            PrivateAuthorizationSlot.CONNECTION_PROFILES -> AbemaRenewalRecordFamily.ROUTE_PROFILES
            PrivateAuthorizationSlot.SOURCE_SETUP, PrivateAuthorizationSlot.PROVIDER_SETUP -> AbemaRenewalRecordFamily.LEGACY_SETUP
            PrivateAuthorizationSlot.PROVIDER_INSTANCES -> AbemaRenewalRecordFamily.PROVIDER_REGISTRY
            PrivateAuthorizationSlot.STREAM_QUALITY -> AbemaRenewalRecordFamily.QUALITY
            PrivateAuthorizationSlot.TWITCH_PROVIDER_PLAYBACK, PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV,
            PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV_LOCAL -> AbemaRenewalRecordFamily.TWITCH_AUTHORIZATION
        }
    }
    return when {
        dynamic(name, "configured-provider-instance-", ".enc", ::configuredProviderInstanceBindingName) -> AbemaRenewalRecordFamily.CONFIGURED_SOURCE
        account(name) -> AbemaRenewalRecordFamily.ABEMA_ACCOUNT
        dynamic(name, "twitch-provider-instance-", "-local-authorization.enc") { twitchProviderInstanceBindingName(it).removeSuffix("-local-authorization") } ||
            dynamic(name, "twitch-catalog-instance-", "-authorization.enc") { twitchCatalogInstanceBindingName(it).removeSuffix("-authorization") } ||
            name == "twitch-own-authorization.enc" -> AbemaRenewalRecordFamily.TWITCH_AUTHORIZATION
        else -> null
    }
}

internal fun abemaRenewalRecordFamily(key: String, selected: String): AbemaRenewalRecordFamily {
    val prefix = key.substringBefore('/')
    val name = key.substringAfter('/', "")
    if (name.isEmpty() || '/' in name) return AbemaRenewalRecordFamily.UNKNOWN
    if (prefix == "shared_prefs") return AbemaRenewalRecordFamily.SHARED_PREFERENCES
    if (prefix != "no_backup") return AbemaRenewalRecordFamily.UNKNOWN
    encryptedFamily(name)?.let { return it }
    // TwitchCatalogGrantStore writes this exact RandomAccessFile marker, not
    // AtomicFile siblings. Its store also refuses the reserved ABEMA identity.
    if (dynamic(name, "twitch-catalog-", ".lock.forget-pending") {
        require(it != defaultProviderInstanceId(PrototypeService.ABEMA))
        "twitch-catalog-$it"
    }) return AbemaRenewalRecordFamily.TWITCH_FORGET_PENDING
    if (name in setOf("connection-profiles.lock", "source-setup.lock", "provider-setup.lock", "provider-instances.lock",
            "stream-quality.lock", "failure-diagnostics.tsv.lock") ||
        dynamic(name, "configured-provider-instance-", ".lock", ::configuredProviderInstanceBindingName) ||
        dynamic(name, "twitch-catalog-", ".lock") { "twitch-catalog-$it" }) return AbemaRenewalRecordFamily.COORDINATION_LOCK
    when (name) {
        "failure-diagnostics.tsv" -> return AbemaRenewalRecordFamily.DIAGNOSTIC_JOURNAL
        "abema-cdn-review.tsv" -> return AbemaRenewalRecordFamily.CDN_REVIEW_JOURNAL
        "prototype-cached-recovery.tsv", "prototype-player-recovery.tsv" -> return AbemaRenewalRecordFamily.RECOVERY_CHECKPOINT
    }
    val base = if (name.endsWith(".bak") || name.endsWith(".new")) name.dropLast(4) else name
    val pending = account(base, ".forget-pending")
    val family = encryptedFamily(base)
    if (pending || base != name && family != null) {
        val selectedName = selected.takeIf { it.startsWith("no_backup/") }?.removePrefix("no_backup/")
        if (selectedName != null && account(selectedName) &&
            (base == selectedName || base == selectedName.removeSuffix(".enc") + ".forget-pending"))
            return AbemaRenewalRecordFamily.SELECTED_ACCOUNT_AUXILIARY
        return if (pending || family == AbemaRenewalRecordFamily.ABEMA_ACCOUNT) AbemaRenewalRecordFamily.ACCOUNT_AUXILIARY
            else AbemaRenewalRecordFamily.PRIVATE_RECORD_AUXILIARY
    }
    return AbemaRenewalRecordFamily.UNKNOWN
}

// This supplements the original strict comparison. No record identity/digest
// enters the returned immutable list or its generated string representation.
internal fun abemaRenewalFamilyMutations(before: Map<String, ByteArray>, after: Map<String, ByteArray>, selected: String): List<AbemaRenewalFamilyMutation> {
    val mutations = (before.keys + after.keys).filter { it != selected &&
        (before[it] == null || after[it] == null || !MessageDigest.isEqual(before.getValue(it), after.getValue(it))) }
        .groupBy { abemaRenewalRecordFamily(it, selected) }
    return AbemaRenewalRecordFamily.entries.mapNotNull { family ->
        val keys = mutations[family] ?: return@mapNotNull null
        AbemaRenewalFamilyMutation(family, keys.count { it in before && it in after }.coerceAtMost(64),
            keys.count { it !in before }.coerceAtMost(64), keys.count { it !in after }.coerceAtMost(64))
    }
}
