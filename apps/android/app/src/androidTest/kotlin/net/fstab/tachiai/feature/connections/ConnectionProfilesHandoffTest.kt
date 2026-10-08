package net.fstab.tachiai.feature.connections

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ConnectionProfilesHandoffTest {
    @get:Rule val compose = createComposeRule()
    private fun intent(action: String, path: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://net.fstab.fixture.connections/$path")
        return Intent(context, ConnectionProfilesActivity::class.java).setAction(action).setType("text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri) else setDataAndType(uri, "text/plain")
                clipData = ClipData.newRawUri("synthetic fixture", uri)
            }
    }
    private fun awaitPreview(endpoint: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(endpoint, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save route").performScrollTo().assertIsNotEnabled()
    }
    private fun launchClean(): ActivityScenario<ConnectionProfilesActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The app clears delivered URI/action before onCreate completes. Scenario must track
        // the clean intent; deliver attachments afterwards through the actual singleTask entry.
        return ActivityScenario.launch(Intent(context, ConnectionProfilesActivity::class.java))
    }
    private fun deliver(action: String, path: String) {
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent(action, path))
    }

    @Test fun sharedFileReachesSecretFreePreviewWithoutSaving() {
        launchClean().use {
            deliver(Intent.ACTION_SEND, "wireguard")
            awaitPreview("Endpoint: vpn.example.test:51820")
        }
    }

    @Test fun openWithSupportsMatchingClipGrantAndRecreationDoesNotReplayImport() {
        launchClean().use { scenario ->
            deliver(Intent.ACTION_VIEW, "proxy")
            awaitPreview("Endpoint: proxy.example.test:3128")
            compose.onNodeWithText("synthetic-password", substring = true).assertDoesNotExist()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("Import preview").assertDoesNotExist()
        }
    }

    @Test fun freshHandoffReplacesPreviewInsteadOfReusingPreviousConfiguration() {
        launchClean().use {
            deliver(Intent.ACTION_SEND, "wireguard")
            awaitPreview("Endpoint: vpn.example.test:51820")
            deliver(Intent.ACTION_VIEW, "proxy")
            awaitPreview("Endpoint: proxy.example.test:3128")
            compose.onNodeWithText("Endpoint: vpn.example.test:51820", substring = true).assertDoesNotExist()
        }
    }
}
