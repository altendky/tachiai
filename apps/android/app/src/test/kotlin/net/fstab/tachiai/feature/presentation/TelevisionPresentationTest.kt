package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelevisionPresentationTest {
    @Test fun televisionUsesUiModeRegardlessOfNightModeOrScreenShape() {
        val configuration = Configuration().apply {
            uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        assertTrue(isTelevision(configuration))
        configuration.uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO
        assertTrue(isTelevision(configuration))
    }

    @Test fun widePhoneAndOtherDeviceTypesDoNotBecomeTelevisions() {
        val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        for (type in listOf(Configuration.UI_MODE_TYPE_NORMAL, Configuration.UI_MODE_TYPE_DESK,
            Configuration.UI_MODE_TYPE_CAR, Configuration.UI_MODE_TYPE_WATCH, Configuration.UI_MODE_TYPE_UNDEFINED)) {
            configuration.uiMode = type or Configuration.UI_MODE_NIGHT_YES
            assertFalse(isTelevision(configuration))
        }
    }
}
