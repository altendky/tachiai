package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.provider.abema.account.AbemaRenewalRecordFamily.*

class AbemaRenewalRecordFamiliesTest {
    private val id = "12345678-1234-4000-8000-123456789abc"
    private val other = "23456789-1234-4000-8000-123456789abc"
    private val selected = "no_backup/abema-account-$id.enc"
    private fun family(name: String) = abemaRenewalRecordFamily("no_backup/$name", selected)

    @Test fun publicRenewalScheduleHasAnExactBoundedFamilyWithoutAcceptingLookalikes() {
        for (suffix in listOf("", ".bak", ".new")) {
            assertEquals(AbemaRenewalRecordFamily.RENEWAL_SCHEDULE, family(AbemaRenewalJobLedger.FILE_NAME + suffix))
        }
        for (name in listOf("abema-renewal-jobs.bin", "abema-renewal-jobs-v1.bin.extra", "ABEMA-renewal-jobs-v1.bin")) {
            assertEquals(AbemaRenewalRecordFamily.UNKNOWN, family(name))
        }
        assertEquals(AbemaRenewalRecordFamily.SHARED_PREFERENCES,
            abemaRenewalRecordFamily("shared_prefs/" + AbemaRenewalJobLedger.FILE_NAME, selected))
        val mutation = abemaRenewalFamilyMutations(emptyMap(),
            mapOf("no_backup/" + AbemaRenewalJobLedger.FILE_NAME to byteArrayOf(1)), selected).single()
        assertEquals(AbemaRenewalRecordFamily.RENEWAL_SCHEDULE, mutation.family)
        assertEquals(1, mutation.added)
    }

    @Test fun onlyExactSourceDefinedNamesAndCanonicalBindingsObtainKnownFamilies() {
        for ((name, expected) in listOf("connection-profiles.enc" to ROUTE_PROFILES, "source-setup.enc" to LEGACY_SETUP,
            "provider-setup.enc" to LEGACY_SETUP, "provider-instances.enc" to PROVIDER_REGISTRY, "stream-quality.enc" to QUALITY,
            "configured-provider-instance-$id.enc" to CONFIGURED_SOURCE, "abema-account-$other.enc" to ABEMA_ACCOUNT,
            "twitch-provider-playback-authorization.enc" to TWITCH_AUTHORIZATION, "twitch-provider-smart-tv-authorization.enc" to TWITCH_AUTHORIZATION,
            "twitch-provider-smart-tv-local-authorization.enc" to TWITCH_AUTHORIZATION,
            "twitch-provider-instance-$id-local-authorization.enc" to TWITCH_AUTHORIZATION,
            "twitch-catalog-instance-$id-authorization.enc" to TWITCH_AUTHORIZATION,
            "provider-instances.lock" to COORDINATION_LOCK, "configured-provider-instance-$id.lock" to COORDINATION_LOCK,
            "twitch-catalog-$id.lock" to COORDINATION_LOCK, "failure-diagnostics.tsv.lock" to COORDINATION_LOCK,
            "failure-diagnostics.tsv" to DIAGNOSTIC_JOURNAL, "abema-cdn-review.tsv" to CDN_REVIEW_JOURNAL,
            "prototype-cached-recovery.tsv" to RECOVERY_CHECKPOINT, "prototype-player-recovery.tsv" to RECOVERY_CHECKPOINT)) {
            assertEquals(name, expected, family(name))
        }
        for (name in listOf("abema-account-${id.uppercase()}.enc", "abema-account-invalid.enc", "connection-profiles.enc.extra",
            "provider-instances.lock.enc", "failure-diagnostics.tsv.enc", "failure-diagnostics.tsv.bak", "UNKNOWN.enc",
            "configured-provider-instance-$id.enc.bak.new", "twitch-provider-instance-00000000-0000-0000-0000-000000000002-local-authorization.enc")) {
            assertEquals(name, UNKNOWN, family(name))
        }
        assertEquals(SHARED_PREFERENCES, abemaRenewalRecordFamily("shared_prefs/PRIVATE_PREFERENCE.xml", selected))
        assertEquals(UNKNOWN, abemaRenewalRecordFamily("shared_prefs/nested/PRIVATE.xml", selected))
    }

    @Test fun auxiliaryFilesRemainStrictlyPreservedAndRequireARecognizedBaseBinding() {
        for (name in listOf("abema-account-$id.enc.bak", "abema-account-$id.enc.new", "abema-account-$id.forget-pending",
            "abema-account-$id.forget-pending.new")) assertEquals(SELECTED_ACCOUNT_AUXILIARY, family(name))
        assertEquals(ACCOUNT_AUXILIARY, family("abema-account-$other.enc.new"))
        assertEquals(ACCOUNT_AUXILIARY, family("abema-account-$other.forget-pending.bak"))
        assertEquals(PRIVATE_RECORD_AUXILIARY, family("connection-profiles.enc.bak"))
        assertEquals(UNKNOWN, family("PRIVATE.enc.new"))
        val auxiliary = "$selected.new"
        val before = mapOf(selected to byteArrayOf(1), auxiliary to byteArrayOf(2))
        val after = before + (selected to byteArrayOf(3)) + (auxiliary to byteArrayOf(4))
        assertEquals(listOf(AbemaRenewalFamilyMutation(SELECTED_ACCOUNT_AUXILIARY, 1, 0, 0)), abemaRenewalFamilyMutations(before, after, selected))
        assertEquals(AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD, abemaRenewalOtherMutations(before, after, selected).kind)
    }

    @Test fun twitchForgetPendingRecognizesOnlyItsActualCanonicalRandomAccessFileMarker() {
        assertEquals(TWITCH_FORGET_PENDING, family("twitch-catalog-$id.lock.forget-pending"))
        assertEquals(TWITCH_FORGET_PENDING, family("twitch-catalog-00000000-0000-0000-0000-000000000002.lock.forget-pending"))
        for (name in listOf("twitch-catalog-${id.uppercase()}.lock.forget-pending", "twitch-catalog-invalid.lock.forget-pending",
            "twitch-catalog-$id.lock.forget-pending.bak", "twitch-catalog-$id.lock.forget-pending.new",
            "twitch-catalog-$id.forget-pending", "twitch-catalog-$id.lock.lock.forget-pending",
            "twitch-catalog-00000000-0000-0000-0000-000000000001.lock.forget-pending")) assertEquals(name, UNKNOWN, family(name))
        val key = "no_backup/twitch-catalog-$id.lock.forget-pending"
        assertEquals(listOf(AbemaRenewalFamilyMutation(TWITCH_FORGET_PENDING, 1, 0, 0)),
            abemaRenewalFamilyMutations(mapOf(key to byteArrayOf(1)), mapOf(key to byteArrayOf(2)), selected))
    }

    @Test fun boundedFamilyCountsSupplementOriginalInventoryComparisonWithoutNamesOrDigests() {
        val removed = "shared_prefs/PRIVATE.xml"
        val added = "no_backup/connection-profiles.enc"
        val before = mapOf(selected to byteArrayOf(1), removed to byteArrayOf(2))
        val after = mapOf(selected to byteArrayOf(3), added to byteArrayOf(4))
        val families = abemaRenewalFamilyMutations(before, after, selected)
        assertEquals(listOf(AbemaRenewalFamilyMutation(ROUTE_PROFILES, 0, 1, 0), AbemaRenewalFamilyMutation(SHARED_PREFERENCES, 0, 0, 1)), families)
        assertEquals(AbemaRenewalOtherMutations(0, 1, 1, AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD),
            abemaRenewalOtherMutations(before, after, selected))
        assertFalse(families.toString().contains("PRIVATE")); assertFalse(families.toString().contains(id))
        val many = (1..100).associate { "no_backup/PRIVATE$it.enc" to byteArrayOf(1) }
        assertEquals(listOf(AbemaRenewalFamilyMutation(UNKNOWN, 0, 0, 64)), abemaRenewalFamilyMutations(many, emptyMap(), selected))
    }
}
