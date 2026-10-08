package net.fstab.tachiai.provider.twitch

import java.net.URI
import net.fstab.tachiai.provider.BrowserResourceCategory
import net.fstab.tachiai.provider.isExactHttpsOrigin

// Public anonymous-page deployment observed 2026-10-05, not a stable API.
// A new deployment fails to unknown; absence is not proof a script wasn't loaded.
// Query/fragment content is neither classified nor retained. No auth endpoints.
internal object TwitchLoginResourceClassifier {
    private val publicAssetPaths = mapOf(
        "/config/settings.a346c0e26e35ab87386587f3d6bd61f2.js" to BrowserResourceCategory.CONFIG_SCRIPT,
        "/assets/21956-1764bec9bf11526bf6b6.js" to BrowserResourceCategory.BOOTSTRAP_SCRIPT,
        "/assets/32683-40511fffc5990b8ef8a4.js" to BrowserResourceCategory.AUTH_UI_SCRIPT,
        "/assets/features.auth.components.auth-form.components.login-cf0495af2eeb95a51623.js" to BrowserResourceCategory.AUTH_UI_SCRIPT,
        "/assets/features.auth.components.standalone-auth-pages-26d6ed7d622beb2880cc.js" to BrowserResourceCategory.AUTH_UI_SCRIPT,
        "/assets/features.auth.components.auth-modal-991846811f3d2a3661a3.js" to BrowserResourceCategory.AUTH_UI_SCRIPT,
    )
    private const val protectionPath = "/149e9513-01fa-4fb0-aad4-566afd725d1b/2d206a39-8ed7-437e-a3be-862e0f06eea3/p.js"

    fun classify(uri: URI): BrowserResourceCategory? = when {
        uri.isExactHttpsOrigin("assets.twitch.tv") -> publicAssetPaths[uri.rawPath]
        uri.isExactHttpsOrigin("k.twitchcdn.net") && uri.rawPath == protectionPath -> BrowserResourceCategory.PROTECTION_SCRIPT
        else -> null
    }
}
