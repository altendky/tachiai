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

private const val TRANSLATION_HELP = "Shows provider titles, tab and group names, channel names and schedule " +
    "titles in this device's language. The original stays until a translation is ready and whenever translation " +
    "is unavailable. Tachiai's own labels are not translated. Search, saved streams and matching keep the original text." +
    "\n\nTranslation runs on this device with Google ML Kit; provider text is not sent to a translation service. " +
    "Turning it on downloads a translation model from Google once for each source language, about 30 MB each. " +
    "ML Kit also reports diagnostics, such as the language pair and timing, but not the text." +
    "\n\nEach provider has a Source language. Auto detects each text's language and keeps text that is already " +
    "in your language or whose language is unclear."

/** The global Translate provider text switch, shown in Providers on every form factor. */
@Composable
internal fun ProviderTextTranslationSection(instances: List<ProviderInstance>, enabled: Boolean) {
    val context = LocalContext.current
    val settings by ProviderTextTranslation.settings.collectAsState()
    val saveFailed by ProviderTextTranslation.saveFailed.collectAsState()
    val target = translationTargetLanguage(LocalConfiguration.current.locales[0])
    // The translator (and ML Kit) is created only after translation is enabled.
    val availabilityFlow = remember(settings.enabled) {
        if (settings.enabled) ProviderTextTranslation.translator(context).availability
        else MutableStateFlow<TranslationAvailability>(TranslationAvailability.Idle)
    }
    val availability by availabilityFlow.collectAsState()
    val fixed = instances.map { settings.sourceLanguage(ProviderTextSource(it.service, it.id)) }.filterNot { it.auto }.map { it.tag }
    LaunchedEffect(settings.enabled, target, fixed) {
        if (settings.enabled && target != null) ProviderTextTranslation.translator(context).prepare(fixed, target)
    }
    Column(Modifier.padding(top = 12.dp)) {
        SettingsSection("Translation", TRANSLATION_HELP)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusIndicator()
            .toggleable(settings.enabled, enabled = enabled, role = Role.Checkbox) { on ->
                ProviderTextTranslation.update(context) { it.copy(enabled = on) }
            }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings.enabled, onCheckedChange = null, enabled = enabled)
            Text("Translate provider text", Modifier.padding(start = 8.dp))
        }
        Text(if (settings.enabled && target == null) "Translation is unavailable: this device language is not supported. Originals are shown."
            else translationAvailabilityText(settings.enabled, availability), style = MaterialTheme.typography.bodySmall)
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
    SettingsSection("Source language", "The language of this provider's titles, used when Translate provider text is on in Providers. " +
        "Auto detects each text's language and keeps the original when unsure; a fixed language treats all of this " +
        "provider's text as that language, which avoids misdetecting short titles such as kanji-only Japanese.",
        status = if (settings.enabled) null else "Translation is off")
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
