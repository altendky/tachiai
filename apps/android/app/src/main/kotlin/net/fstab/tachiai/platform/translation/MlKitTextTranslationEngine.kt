package net.fstab.tachiai.platform.translation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.fstab.tachiai.presentation.TextTranslationEngine

// On-device ML Kit translation (#643). Text never leaves the device; models
// download from Google through Android's DownloadManager only after a request
// while the user has translation enabled. The manifest removes ML Kit's
// startup provider, so nothing here initializes until first use.
internal class MlKitTextTranslationEngine(context: Context) : TextTranslationEngine {
    private val context = context.applicationContext
    private val lock = Any()
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private var identifier: LanguageIdentifier? = null

    private fun initialize() { MlKitContext.initializeIfNeeded(context) }

    override fun supports(language: String) = TranslateLanguage.fromLanguageTag(language) != null

    override suspend fun identify(text: String): String? {
        val client = synchronized(lock) {
            identifier ?: run {
                initialize()
                LanguageIdentification.getClient(LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(CONFIDENCE_THRESHOLD).build()).also { identifier = it }
            }
        }
        return client.identifyLanguage(text).await().takeUnless { it == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG }
    }

    private fun translator(source: String, target: String): Translator = synchronized(lock) {
        translators.getOrPut(source to target) {
            initialize()
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(checkNotNull(TranslateLanguage.fromLanguageTag(source)))
                .setTargetLanguage(checkNotNull(TranslateLanguage.fromLanguageTag(target))).build())
        }
    }

    override suspend fun prepare(source: String, target: String) {
        translator(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        translator(source, target).translate(text).await()

    private companion object {
        const val CONFIDENCE_THRESHOLD = 0.5f
        val direct = Executor { it.run() }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(direct) { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }
}
