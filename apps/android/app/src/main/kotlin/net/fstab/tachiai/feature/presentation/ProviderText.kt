package net.fstab.tachiai.feature.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.feature.connections.providerTextTranslationStore
import net.fstab.tachiai.platform.translation.MlKitTextTranslationEngine
import net.fstab.tachiai.presentation.ProviderTextSource
import net.fstab.tachiai.presentation.catalogTitleSource
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.presentation.ProviderTextTranslationSettings
import net.fstab.tachiai.presentation.ProviderTextTranslator
import net.fstab.tachiai.presentation.TranslationRequest
import net.fstab.tachiai.presentation.translationTargetLanguage

// Process-wide owner of the translation preference and the lazily created
// on-device translator (#643). Each process (picker, settings, viewer) reads
// the same encrypted preference; nothing here runs ML Kit while it is off.
internal object ProviderTextTranslation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storage = Dispatchers.IO.limitedParallelism(1)
    private val mutableSettings = MutableStateFlow(ProviderTextTranslationSettings())
    val settings: StateFlow<ProviderTextTranslationSettings> = mutableSettings
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed
    @Volatile private var loaded = false
    @Volatile private var instance: ProviderTextTranslator? = null
    private val viewRequests = mutableSetOf<TranslationRequest>()

    fun translator(context: Context): ProviderTextTranslator = instance ?: synchronized(this) {
        instance ?: ProviderTextTranslator(MlKitTextTranslationEngine(context), scope).also { instance = it }
    }

    /** Rereads the stored preference, for example when a screen resumes after another process changed it. */
    fun refresh(context: Context, then: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch(storage) {
            // An unreadable record keeps translation off: originals are always safe.
            runCatching { providerTextTranslationStore(app).read() }.onSuccess { mutableSettings.value = it }
            loaded = true
            withContext(Dispatchers.Main) { then() }
        }
    }

    fun update(context: Context, transform: (ProviderTextTranslationSettings) -> ProviderTextTranslationSettings) {
        val app = context.applicationContext
        scope.launch(storage) {
            runCatching { providerTextTranslationStore(app).update(transform) }
                .onSuccess { mutableSettings.value = it; mutableSaveFailed.value = false }
                .onFailure { mutableSaveFailed.value = true }
        }
    }

    /**
     * For View-based screens: returns the text to show now (a cached translation
     * or the original) and calls [changed] on the main thread when a
     * translation becomes available, so the caller can rebuild its labels.
     */
    fun displayNow(context: Context, text: String, source: ProviderTextSource?, changed: () -> Unit): String {
        if (source == null) return text
        if (!loaded) { refresh(context, changed); return text }
        val request = settings.value.request(text, source, translationTargetLanguage(Locale.getDefault())) ?: return text
        val translator = translator(context)
        translator.cached(request)?.let { return it }
        if (synchronized(viewRequests) { viewRequests.add(request) }) scope.launch {
            val translated = try { translator.translate(request) } finally { synchronized(viewRequests) { viewRequests.remove(request) } }
            if (translated != null) withContext(Dispatchers.Main) { changed() }
        }
        return text
    }
}

internal class ProviderTextDisplay(
    val settings: ProviderTextTranslationSettings,
    val target: String?,
    val translator: () -> ProviderTextTranslator,
)

internal val LocalProviderTextDisplay = staticCompositionLocalOf<ProviderTextDisplay?> { null }

@Composable
internal fun ProvideProviderText(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by ProviderTextTranslation.settings.collectAsState()
    LifecycleResumeEffect(context) {
        ProviderTextTranslation.refresh(context)
        onPauseOrDispose { }
    }
    val target = translationTargetLanguage(LocalConfiguration.current.locales[0])
    val display = remember(settings, target, context) {
        ProviderTextDisplay(settings, target) { ProviderTextTranslation.translator(context) }
    }
    CompositionLocalProvider(LocalProviderTextDisplay provides display, content = content)
}

/**
 * The single display path for provider-supplied text. It returns the original
 * immediately and recomposes with the translation once ready. Callers keep the
 * original for keys, search, matching and storage.
 */
@Composable
internal fun providerText(text: String, source: ProviderTextSource?): String {
    val display = LocalProviderTextDisplay.current
    val request = if (display == null || source == null) null else display.settings.request(text, source, display.target)
    val shown = remember(request, text) {
        mutableStateOf(request?.let { display?.translator?.invoke()?.cached(it) } ?: text)
    }
    if (request != null && display != null) LaunchedEffect(request) {
        display.translator().translate(request)?.let { shown.value = it }
    }
    return shown.value
}

/** A catalog title: provider text unless Tachiai made it for this resource. */
@Composable
internal fun catalogTitle(resource: CatalogResource, title: String, source: ProviderTextSource?): String =
    providerText(title, catalogTitleSource(resource, title, source))
