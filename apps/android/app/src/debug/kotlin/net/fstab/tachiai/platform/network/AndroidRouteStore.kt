package net.fstab.tachiai.platform.network

import android.content.Context
import java.io.File
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot

internal fun connectionProfileStore(context: Context) = ConnectionProfileStore(
    AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.CONNECTION_PROFILES),
    File(context.noBackupFilesDir, "connection-profiles.lock"),
)
