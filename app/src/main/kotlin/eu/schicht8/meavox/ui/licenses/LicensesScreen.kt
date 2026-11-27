// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.licenses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.schicht8.meavox.R
import eu.schicht8.meavox.core.model.SeparationModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The third-party licenses screen (#21): what the app contains or downloads, each with its license.
 * Opening an entry shows its license texts; back returns to the list. [modelName] names a Separation
 * Model's tier in the UI's language (ADR 21).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesRoute(modelName: (SeparationModelId) -> String) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val notice = ThirdPartyNotices.all.firstOrNull { it.name == open }
    BackHandler(enabled = notice != null) { open = null }
    Scaffold(topBar = { TopAppBar(title = { Text(notice?.let { title(it, modelName) } ?: stringResource(R.string.licenses_title)) }) }) { padding ->
        if (notice == null) {
            NoticeList(Modifier.padding(padding), modelName, onOpen = { open = it.name })
        } else {
            LicenseTexts(notice, Modifier.padding(padding))
        }
    }
}

/** The work's name, with the tier name for a Separation Model's weights: "Spleeter 2-stems (Fast)". */
private fun title(notice: ThirdPartyNotice, modelName: (SeparationModelId) -> String): String =
    notice.model?.let { "${notice.name} (${modelName(it)})" } ?: notice.name

@Composable
private fun licenseName(notice: ThirdPartyNotice): String = notice.license ?: stringResource(R.string.licenses_no_license)

@Composable
private fun NoticeList(modifier: Modifier, modelName: (SeparationModelId) -> String, onOpen: (ThirdPartyNotice) -> Unit) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.licenses_intro),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ThirdPartyNotices.all, key = { it.name }) { notice ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(notice) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title(notice, modelName), style = MaterialTheme.typography.titleMedium)
                Text(
                    licenseName(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                kindNote(notice.kind)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

/** What the screen says about how the app holds the work, if more than "contains it". */
@Composable
private fun kindNote(kind: ThirdPartyNotice.Kind): String? = when (kind) {
    ThirdPartyNotice.Kind.Contained -> null
    ThirdPartyNotice.Kind.ReplaceableLibrary -> stringResource(R.string.licenses_replaceable)
    ThirdPartyNotice.Kind.ModelWeights -> stringResource(R.string.licenses_model_weights)
    ThirdPartyNotice.Kind.UnlicensedModelWeights -> stringResource(R.string.licenses_unlicensed_model_weights)
}

/** The notice's license texts, one paragraph per item so long texts (ONNX Runtime's notices) scroll smoothly. */
@Composable
private fun LicenseTexts(notice: ThirdPartyNotice, modifier: Modifier) {
    val context = LocalContext.current
    val paragraphs by produceState(emptyList<String>(), notice) {
        value = withContext(Dispatchers.IO) {
            notice.texts.flatMap { path ->
                context.assets.open("licenses/$path").bufferedReader().use { it.readText() }.split(Regex("\n\\s*\n"))
            }
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(licenseName(notice), style = MaterialTheme.typography.titleSmall)
                Text(notice.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                kindNote(notice.kind)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(paragraphs) { paragraph ->
            Text(paragraph.trim(), Modifier.padding(vertical = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
