package net.fstab.tachiai.provider.abema.account

internal fun abemaAccountReadinessMessage(result: AbemaAccountReadinessResult): String = when (result) {
    AbemaAccountReadinessResult.TEMPORARY_FAILURE ->
        "ABEMA connection is temporarily unavailable. Retry when the selected connection is available."
    AbemaAccountReadinessResult.INTERACTION_REQUIRED ->
        "ABEMA needs sign-in or account confirmation. Open ABEMA account in Providers."
    AbemaAccountReadinessResult.STORAGE_FAILURE ->
        "The saved ABEMA account could not be read or updated. Provider access remains unavailable."
    AbemaAccountReadinessResult.FAILURE ->
        "ABEMA account could not be renewed. Open ABEMA account in Providers to check the connection."
    AbemaAccountReadinessResult.HANDOFF -> "Renewing the remembered ABEMA account…"
    AbemaAccountReadinessResult.READY -> "ABEMA account is ready."
}
