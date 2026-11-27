package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import net.fstab.tachiai.R
import net.fstab.tachiai.feature.presentation.ProviderTextTranslation
import net.fstab.tachiai.feature.presentation.RemoteDropdownMenuItem as DropdownMenuItem
import net.fstab.tachiai.feature.presentation.RemoteOutlinedButton as OutlinedButton
import net.fstab.tachiai.feature.presentation.RemoteRadioButton as RadioButton
import net.fstab.tachiai.feature.presentation.tvFocusIndicator
import net.fstab.tachiai.presentation.*

private const val TRANSLATION_HELP = "Shows this provider's titles, tab and group names, channel names and schedule " +
    "titles in this device's language. The original stays until a translation is ready and whenever translation " +
    "is unavailable. Tachiai's own labels are not translated. Search, saved streams and matching keep the original text. " +
    "Other providers keep their own setting." +
    "\n\nTranslation runs on this device with Google ML Kit; provider text is not sent to a translation service. " +
    "Turning it on downloads a translation model from Google once for each source language, about 30 MB each. " +
    "ML Kit also reports diagnostics, such as the language pair and timing, but not the text." +
    "\n\nIt uses the Source language below. Auto detects each text's language and keeps text that is already " +
    "in your language or whose language is unclear."

/** A provider instance's Translate provider text switch (#765), beside its Source language. Off by default. */
@Composable
internal fun ProviderTextSettings(instance: ProviderInstance, instances: List<ProviderInstance>, enabled: Boolean) {
    val context = LocalContext.current
    val settings by ProviderTextTranslation.settings.collectAsState()
    val saveFailed by ProviderTextTranslation.saveFailed.collectAsState()
    val on = settings.providerText(instance.id)
    val target = translationTargetLanguage(LocalConfiguration.current.locales[0])
    val language = settings.sourceLanguage(ProviderTextSource(instance.service, instance.id))
    // The shared translator (and ML Kit) is created only once this is on.
    val availabilityFlow = remember(on) {
        if (on) ProviderTextTranslation.translator(context).availability
        else MutableStateFlow<TranslationAvailability>(TranslationAvailability.Idle)
    }
    val availability by availabilityFlow.collectAsState()
    LaunchedEffect(on, target, language) {
        if (on && target != null && !language.auto) ProviderTextTranslation.translator(context).prepare(listOf(language.tag), target)
    }
    Column {
        SettingsSection("Provider text", TRANSLATION_HELP)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()
            .toggleable(on, enabled = enabled, role = Role.Checkbox) { value ->
                val ids = instances.map { it.id }.toSet()
                ProviderTextTranslation.update(context) { it.withProviderText(instance, value, ids) }
            }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = null, enabled = enabled)
            Text("Translate provider text", Modifier.padding(start = 8.dp))
        }
        Text(if (on && target == null) "Translation is unavailable: this device language is not supported. Originals are shown."
            else translationAvailabilityText(on, availability), style = MaterialTheme.typography.bodySmall)
        if (saveFailed) Text("The translation setting could not be saved. Try again.", color = MaterialTheme.colorScheme.error)
    }
}

/** A provider instance's Source language, in its provider details. */
@Composable
internal fun ProviderSourceLanguageSettings(instance: ProviderInstance, instances: List<ProviderInstance>, enabled: Boolean) {
    val context = LocalContext.current
    val settings by ProviderTextTranslation.settings.collectAsState()
    val current = settings.sourceLanguage(ProviderTextSource(instance.service, instance.id))
    val default = defaultProviderSourceLanguage(instance.service)
    var expanded by remember(instance.id) { mutableStateOf(false) }
    val choices = listOf(ProviderSourceLanguage.AUTO) + PROVIDER_SOURCE_LANGUAGES.map { ProviderSourceLanguage(it.first) }
    fun label(language: ProviderSourceLanguage) =
        providerSourceLanguageLabel(language) + if (language == default) " (default)" else ""
    SettingsSection("Source language", "The language of this provider's titles, used when Translate provider text is on, " +
        "and of text in its video when Translate text in video is on. " +
        "Auto detects each text's language and keeps the original when unsure; a fixed language treats all of this " +
        "provider's text as that language, which avoids misdetecting short titles such as kanji-only Japanese.",
        status = if (settings.providerText(instance.id) || settings.frameText(instance.id)) null else "Translation is off")
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Source language: ${label(current)}" },
            shape = MaterialTheme.shapes.small) {
            Text(label(current), Modifier.weight(1f))
            Icon(painterResource(R.drawable.settings_expand), contentDescription = null)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { language ->
                DropdownMenuItem(text = { Text(label(language)) }, leadingIcon = {
                    RadioButton(selected = language == current, onClick = null)
                }, onClick = {
                    expanded = false
                    val ids = instances.map { it.id }.toSet()
                    ProviderTextTranslation.update(context) { it.withSourceLanguage(instance, language, ids) }
                }, modifier = Modifier.semantics { selected = language == current })
            }
        }
    }
}

private const val FRAME_TEXT_HELP = "Recognizes text that appears in the picture of this provider's video, such as name, " +
    "result and score cards, and shows a translation over it in this device's language. It runs on the main " +
    "(full-size) video only, never the floating feed. Only text that stays put is translated; moving text such as " +
    "tickers is skipped, and each translation stays until its text changes or disappears." +
    "\n\nThis is the default for this provider. While watching, More → Translate text in video turns it on or off " +
    "for that stream only; the next stream starts from this default." +
    "\n\nRecognition and translation run on this device with Google ML Kit. Video frames and recognized text are " +
    "kept in memory only: they are not saved or sent off this device, but the translator may write recognized " +
    "text and its translation to this device's system log. It uses the Source language above and " +
    "the same translation models as Translate provider text, downloaded from Google once for each source language, " +
    "about 30 MB each. ML Kit also reports diagnostics, such as timing, image size and the language pair, but not " +
    "the images or text."

/** A provider instance's Translate text in video default (#664), beside its Source language. Off by default. */
@Composable
internal fun ProviderFrameTextSettings(instance: ProviderInstance, instances: List<ProviderInstance>, enabled: Boolean) {
    val context = LocalContext.current
    val settings by ProviderTextTranslation.settings.collectAsState()
    val on = settings.frameText(instance.id)
    val target = translationTargetLanguage(LocalConfiguration.current.locales[0])
    val language = settings.sourceLanguage(ProviderTextSource(instance.service, instance.id))
    // The shared translator (and ML Kit) is created only once this is on.
    val availabilityFlow = remember(on) {
        if (on) ProviderTextTranslation.translator(context).availability
        else MutableStateFlow<TranslationAvailability>(TranslationAvailability.Idle)
    }
    val availability by availabilityFlow.collectAsState()
    LaunchedEffect(on, target, language) {
        if (on && target != null && !language.auto) ProviderTextTranslation.translator(context).prepare(listOf(language.tag), target)
    }
    Column {
        SettingsSection("Text in video", FRAME_TEXT_HELP)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()
            .toggleable(on, enabled = enabled, role = Role.Checkbox) { value ->
                val ids = instances.map { it.id }.toSet()
                ProviderTextTranslation.update(context) { it.withFrameText(instance, value, ids) }
            }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = null, enabled = enabled)
            Text("Translate text in video", Modifier.padding(start = 8.dp))
        }
        Text(when {
            !on -> "Off. Text in the video is shown as broadcast."
            target == null -> "Translation is unavailable: this device language is not supported."
            else -> when (availability) {
                is TranslationAvailability.Downloading, is TranslationAvailability.DownloadFailed,
                is TranslationAvailability.TargetUnsupported -> translationAvailabilityText(true, availability)
                else -> "On. Steady text in the main video is translated in place."
            }
        }, style = MaterialTheme.typography.bodySmall)
    }
}
