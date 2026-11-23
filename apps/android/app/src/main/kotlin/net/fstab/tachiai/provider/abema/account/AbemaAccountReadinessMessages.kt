package net.fstab.tachiai.provider.abema.account

// One message set for the saved ABEMA account on every screen: Watch, Episodes,
// viewer feeds and Providers (#635 step 7). Ready shows nothing and proceeds.
// Renewing shows while a renewal runs or waits to retry. Needs you (a different
// account, or a renewal that cannot continue by itself) points to the account
// in Providers. Temporarily unavailable comes only after the renewal's retries
// are exhausted. A storage problem keeps its own needs-attention text.
internal const val ABEMA_ACCOUNT_CHECKING_MESSAGE = "Checking account connection…"
internal const val ABEMA_ACCOUNT_RENEWING_MESSAGE = "Renewing the remembered ABEMA account…"

internal fun abemaAccountReadinessMessage(result: AbemaAccountReadinessResult): String? = when (result) {
    AbemaAccountReadinessResult.TEMPORARY_FAILURE ->
        "ABEMA connection is temporarily unavailable. Retry when the selected connection is available."
    AbemaAccountReadinessResult.INTERACTION_REQUIRED ->
        "ABEMA is signed in to a different account. Open ABEMA account in Providers and choose the saved account."
    AbemaAccountReadinessResult.STORAGE_FAILURE ->
        "The saved ABEMA account could not be read or updated. Open ABEMA account in Providers to retry validation or Forget."
    AbemaAccountReadinessResult.FAILURE ->
        "ABEMA account could not be renewed. Open ABEMA account in Providers to check the connection."
    AbemaAccountReadinessResult.HANDOFF -> ABEMA_ACCOUNT_RENEWING_MESSAGE
    AbemaAccountReadinessResult.READY -> null
}
