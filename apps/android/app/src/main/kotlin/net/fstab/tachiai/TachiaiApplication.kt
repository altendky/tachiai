package net.fstab.tachiai

import android.app.Application
import net.fstab.tachiai.platform.network.installRouteLeaseSource
import net.fstab.tachiai.provider.abema.account.AndroidAbemaRenewalJobs

class TachiaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installRouteLeaseSource(this)
        AndroidAbemaRenewalJobs.initialize(this)
    }
}
