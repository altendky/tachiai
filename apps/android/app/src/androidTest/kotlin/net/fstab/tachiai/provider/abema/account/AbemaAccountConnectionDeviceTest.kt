package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.presentation.validProviderInstanceId
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Opt-in read-only saved-route enforcement experiment. Default runs skip BEFORE
// any saved-store read, service binding or provider request. It never writes a
// grant, navigates, polls a token, or exports guest/account fields.
class AbemaAccountConnectionDeviceTest {
    @Test fun optInSavedRouteProfileAuthorizationEnforcement() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("abemaProfileEnforcement") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == context.packageName)
        val instanceId = args.getString("abemaProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
        val main = Handler(Looper.getMainLooper())
        val binding = androidAbemaAccountConnectionBinding(context, instanceId)
        val controller = AbemaAccountConnectionController(binding, AbemaAccountWorkers.worker, AbemaAccountWorkers.cancellation,
            { action -> main.post(action) }, { millis, action -> main.postDelayed(action, millis) },
            SystemClock::elapsedRealtime, System::currentTimeMillis)
        try {
            instrumentation.runOnMainSync { controller.checkEnforcement() }
            val end = SystemClock.elapsedRealtime() + 110000
            var outcome: AbemaAccountProgress? = null
            while (SystemClock.elapsedRealtime() < end) {
                val state = controller.state.value
                if (!state.busy && state.progress != AbemaAccountProgress.READING) { outcome = state.progress; break }
                Thread.sleep(100)
            }
            val category = when (outcome) {
                AbemaAccountProgress.ENFORCEMENT_PROVEN -> "PROFILE_AUTH_ENFORCEMENT_ESTABLISHED"
                AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN -> "PROFILE_AUTH_ENFORCEMENT_NOT_ESTABLISHED"
                else -> "PROFILE_AUTH_ENFORCEMENT_INCONCLUSIVE"
            }
            Log.d("TachiaiAbemaAccount", category)
            assertTrue(category in setOf("PROFILE_AUTH_ENFORCEMENT_ESTABLISHED", "PROFILE_AUTH_ENFORCEMENT_NOT_ESTABLISHED", "PROFILE_AUTH_ENFORCEMENT_INCONCLUSIVE"))
            assertFalse(controller.state.value.canApprove); assertFalse(controller.state.value.canContinue)
            assertNotEquals(AbemaAccountStatus.CONNECTED, controller.state.value.status)
        } finally { instrumentation.runOnMainSync { controller.close(); main.removeCallbacksAndMessages(null) } }
    }
}
