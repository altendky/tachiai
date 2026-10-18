package net.fstab.tachiai.feature.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test

// Injected launchers only; these tests never start Google Home or a Cast session.
class ScreenSharingTargetTest {
    @Test fun castSettingsLaunchHasNoMediaUrlPayloadOrHomeDependency() {
        var opened: Intent? = null
        assertTrue(openScreenSharingTarget(ScreenSharingTarget.CAST_SETTINGS,
            { error("Cast settings must work without Google Home") }, { opened = it }))
        assertEquals(Settings.ACTION_CAST_SETTINGS, opened!!.action)
        assertNull(opened!!.data)
        assertNull(opened!!.extras)
        assertNull(opened!!.component)
    }

    @Test fun googleHomeUsesNormalPackageLauncherWithoutAddingPrivateExtras() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(GOOGLE_HOME_PACKAGE)
        var opened: Intent? = null
        assertTrue(openScreenSharingTarget(ScreenSharingTarget.GOOGLE_HOME, { home }, { opened = it }))
        assertSame(home, opened)
        assertNull(opened!!.data)
        assertNull(opened!!.extras)
    }

    @Test fun absentGoogleHomeDoesNotLaunchAnything() {
        assertFalse(openScreenSharingTarget(ScreenSharingTarget.GOOGLE_HOME, { null }) {
            fail("Unavailable launcher must not be invoked")
        })
    }

    @Test fun missingOrRestrictedHandlersAreSafeForEitherDestination() {
        ScreenSharingTarget.entries.forEach { target ->
            listOf(ActivityNotFoundException(), SecurityException()).forEach { failure ->
                assertFalse(openScreenSharingTarget(target, { Intent(Intent.ACTION_MAIN) }) { throw failure })
            }
        }
    }

    @Test fun deniedHomeLookupDoesNotLaunch() {
        assertFalse(openScreenSharingTarget(ScreenSharingTarget.GOOGLE_HOME, { throw SecurityException() }) {
            fail("Denied launcher lookup must not launch")
        })
    }
}
