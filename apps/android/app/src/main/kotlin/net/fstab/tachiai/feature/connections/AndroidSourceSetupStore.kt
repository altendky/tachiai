package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.File
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot

internal fun sourceSetupStore(context: Context): SourceSetupStore = SourceSetupStore(
    AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.SOURCE_SETUP),
    File(context.noBackupFilesDir, "source-setup.lock"),
)

internal fun providerSetupStore(context: Context): ProviderSetupStore = ProviderSetupStore(
    AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.PROVIDER_SETUP),
    File(context.noBackupFilesDir, "provider-setup.lock"),
)

internal fun providerInstanceStore(context: Context): ProviderInstanceStore = ProviderInstanceStore(
    AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.PROVIDER_INSTANCES),
    File(context.noBackupFilesDir, "provider-instances.lock"),
)

internal fun legacyProviderSettings(context: Context) = providerSetupStore(context).read(sourceSetupStore(context).read())
